import concurrent
import threading
import time
import logging
import requests

from concurrent.futures import ThreadPoolExecutor
from typing import List
from urllib.parse import urlparse, urlunparse
from dataclasses import dataclass


class ExecutorPool:
    def __init__(self):
        self._executor = None
        self._ref_count = 0

    def add_ref(self):
        self._ref_count += 1
        if self._executor is None:
            self._executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)

    def remove_ref(self):
        self._ref_count -= 1
        if self._ref_count <= 0:
            (pool, self._executor) = (self._executor, None)
            if pool is not None:
                pool.shutdown(wait=False)
        if self._executor is not None:
            self._executor.shutdown(wait=False)

    def submit(self, fn, *args, **kwargs):
        return self._executor.submit(fn, *args, **kwargs)

@dataclass
class Config:
    nodes: List[str]
    schema: str = "http"
    port: int = 8080
    datacenter: str = None
    rack: str = None
    aws_region_name: str = "fake-alternator-lb-region"
    aws_access_key_id: str = None
    aws_secret_access_key: str = None
    update_interval: int = 10

    def _get_nodes(self) -> List[str]:
        nodes = []
        for node in self.nodes:
            uri = urlunparse((self.schema, node + ":" + str(self.port), "", "", "", ""))
            parsed_uri = urlparse(uri)
            if not parsed_uri.scheme or not parsed_uri.netloc:
                raise ValueError(f"Invalid URI: {uri}")
            nodes.append(uri)
        return nodes



class AlternatorLB:
    """
    Manages live Alternator nodes by tracking available nodes, periodically updating them,
    and providing methods for retrieving node information.

    This class is responsible for:
    - Maintaining a list of live Alternator nodes.
    - Periodically updating the list of nodes by querying an endpoint.
    - Providing methods to retrieve nodes in a round-robin manner.
    - Ensuring compatibility with AWS DynamoDB clients by modifying endpoint resolution.

    How to use:
    ```
        import botocore.session

        session = botocore.session.get_session()
        dynamodb = session.create_client('dynamodb', region_name='us-east-1')
        lb = AlternatorLB(['x.x.x.x'], 'http', 8080)
        nodes.start()
        nodes.wrap_dynamodb_client(dynamodb)
    ```
    """
    _pool = ExecutorPool()
    _instance_counter = 0
    _logger = logging.getLogger('AlternatorLB')

    def __del__(self):
        self._pool.remove_ref()

    def __init__(self, config: Config):
        self._pool.add_ref()
        self._config = config
        if not self._config.nodes:
            raise ValueError("liveNodes cannot be null or empty")

        self._initial_nodes = config._get_nodes()
        self._live_nodes = self._initial_nodes[:]
        self._live_nodes_lock = threading.Lock()
        self._next_live_node_index = 0
        self._updating = False
        self._next_update_time = 0

    @staticmethod
    def _validate_uri(uri: str):
        parsed_uri = urlparse(uri)
        if not parsed_uri.scheme or not parsed_uri.netloc:
            raise ValueError(f"Invalid URI: {uri}")

    def _update_nodes_if_needed(self):
        if self._updating:
            return
        now = time.time()
        if self._next_update_time >= now:
            return
        with self._live_nodes_lock:
            if self._next_update_time >= now or self._updating:
                return
            self._updating = True
        self._pool.submit(self._update_live_nodes)

    def _next_alternator_node(self) -> str:
        self._update_nodes_if_needed()
        with self._live_nodes_lock:
            node = self._live_nodes[self._next_live_node_index % len(self._live_nodes)]
            self._next_live_node_index += 1
            return node

    def _next_as_uri(self, path: str = "", query: str = "") -> str:
        if not self._live_nodes:
            with self._live_nodes_lock:
                self._live_nodes = self._initial_nodes[:]

        node = None
        with self._live_nodes_lock:
            node = self._live_nodes[self._next_live_node_index % len(self._live_nodes)]

        self._next_live_node_index += 1
        parsed = urlparse(node)
        new_uri = urlunparse((parsed.scheme, parsed.netloc, path, "", query, ""))
        return new_uri

    def _update_live_nodes(self):
        new_hosts = self._get_nodes(self._next_as_local_nodes_uri())
        if new_hosts:
            with self._live_nodes_lock:
                self._live_nodes = new_hosts
                self._next_update_time = time.time() + self._config.update_interval
                self._updating = False
            self._logger.debug(f"Updated hosts to {self._live_nodes}")

    def _get_nodes(self, uri: str) -> List[str]:
        try:
            response = requests.get(uri, timeout=5)
            if response.status_code != 200:
                return []

            nodes = response.json()
            return [self._host_to_uri(host) for host in nodes if host]
        except Exception as e:
            self._logger.warning(f"Failed to fetch nodes from {uri}: {e}")
            return []

    def _host_to_uri(self, host: str) -> str:
        return f"{self._config.schema}://{host}:{self._config.port}"

    def _next_as_local_nodes_uri(self) -> str:
        query = ""
        if self._config.rack:
            query += f"rack={self._config.rack}"
        if self._config.datacenter:
            query += ("&" if query else "") + f"dc={self._config.datacenter}"

        return self._next_as_uri("/localnodes", query)

    def check_if_rack_and_datacenter_set_correctly(self):
        if not self._config.rack and not self._config.datacenter:
            return

        nodes = self._get_nodes(self._next_as_local_nodes_uri())
        if not nodes:
            raise ValueError("Node returned empty list, datacenter or rack are set incorrectly")

    def check_if_rack_datacenter_feature_is_supported(self) -> bool:
        uri = self._next_as_uri("/localnodes")
        fake_rack_uri = f"{uri}?rack=fakeRack"

        hosts_with_fake_rack = self._get_nodes(fake_rack_uri)
        hosts_without_rack = self._get_nodes(uri)

        if not hosts_without_rack:
            raise RuntimeError(f"Host {uri} returned empty list")

        return len(hosts_with_fake_rack) != len(hosts_without_rack)

    def get_known_nodes(self):
        with self._live_nodes_lock:
            return self._live_nodes[:]

    def new_dynamodb_client(self, key: str = "", secret: str = "", region: str = "") -> object:
        import botocore.session
        session = botocore.session.get_session()
        if not secret:
            secret = self._config.aws_secret_access_key
        if not key:
            key = self._config.aws_access_key_id
        if not region:
            region = self._config.aws_region_name
        ddb = session.create_client('dynamodb', region_name=region, aws_access_key_id=key, aws_secret_access_key=secret)
        self.patch_dynamodb_client(ddb)
        return ddb

    def patch_dynamodb_client(self, client):
        from botocore.regions import EndpointRulesetResolver

        current_resolver = getattr(client, '_ruleset_resolver', None)
        if not current_resolver:
            raise Exception("looks like client is not a boto DynamoDB client, it has no _ruleset_resolver")
        if current_resolver.__class__ != EndpointRulesetResolver:
            raise Exception("client._ruleset_resolver has unexpected class.")

        try:
            if not client.meta.config.region_name:
                raise ValueError("client can't work properly with empty region name")
        except AttributeError:
            raise Exception("client has no meta.config.region_name, looks like it's not a botocore DynamoDB client.")

        orig = current_resolver.construct_endpoint

        def construct_endpoint(
                operation_model,
                call_args,
                request_context,
        ):
            from botocore.endpoint_provider import RuleSetEndpoint
            endpoint_info = orig(operation_model, call_args, request_context)
            if "dynamodb." not in endpoint_info.url:
                return endpoint_info
            return RuleSetEndpoint(
                url=self._next_alternator_node(),
                properties=endpoint_info.properties,
                headers=endpoint_info.headers)

        setattr(current_resolver, 'construct_endpoint', construct_endpoint)

