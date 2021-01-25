#include <aws/core/Aws.h>
#include <aws/dynamodb/DynamoDBClient.h>
#include <aws/core/http/standard/StandardHttpRequest.h>
#include <aws/dynamodb/model/DescribeEndpointsRequest.h>
#include <mutex>
#include <random>

class AlternatorClient : public Aws::DynamoDB::DynamoDBClient {
protected:
	Aws::String _protocol;
	Aws::String _port;
	mutable std::vector<Aws::Http::URI> _nodes;
	mutable std::mutex _nodes_mutex;
	mutable size_t _node_idx = 0;
	std::unique_ptr<std::thread> _node_updater;
	std::atomic<bool> _keep_updating;
public:
	AlternatorClient(Aws::String protocol, Aws::String control_addr, Aws::String port,
			const Aws::Client::ClientConfiguration &clientConfiguration = Aws::Client::ClientConfiguration())
		: Aws::DynamoDB::DynamoDBClient(clientConfiguration)
		, _protocol(protocol)
		, _port(port) {
			Aws::Http::URI initial_node(protocol + "://" + control_addr + ":" + port);
			_nodes.push_back(std::move(initial_node));
			FetchLocalNodes();
		}

	virtual ~AlternatorClient() {
		_keep_updating = false;
		if (_node_updater) {
			_node_updater->join();
		}
	}

	virtual void BuildHttpRequest(const Aws::AmazonWebServiceRequest &request, const std::shared_ptr< Aws::Http::HttpRequest > &httpRequest) const override {
		Aws::Http::URI next = NextNode();
		httpRequest->GetUri() = std::move(next);
		return Aws::DynamoDB::DynamoDBClient::BuildHttpRequest(request, httpRequest);
	}

	void FetchLocalNodes() {
		Aws::Http::URI contact_node;
		{
			std::lock_guard<std::mutex> guard(_nodes_mutex);
			assert(!_nodes.empty());
			contact_node = RandomNode();
			contact_node.SetPath(contact_node.GetPath() + "/localnodes");
		}
		std::shared_ptr<Aws::Http::HttpRequest> request(new Aws::Http::Standard::StandardHttpRequest(contact_node, Aws::Http::HttpMethod::HTTP_GET));
		request->SetResponseStreamFactory([] { return new std::stringstream; });
		std::shared_ptr<Aws::Http::HttpResponse> response = MakeHttpRequest(request);
		Aws::Utils::Json::JsonValue json_raw = response->GetResponseBody();
		Aws::Utils::Json::JsonView json = json_raw.View();
		if (json.IsListType()) {
			std::vector<Aws::Http::URI> nodes;
			Aws::Utils::Array<Aws::Utils::Json::JsonView> endpoints = json.AsArray();
			Aws::Utils::Json::JsonView* raw_endpoints = endpoints.GetUnderlyingData();
			for (size_t i = 0; i < endpoints.GetLength(); ++i) {
				const Aws::Utils::Json::JsonView& element = raw_endpoints[i];
				if (element.IsString()) {
					nodes.push_back(Aws::Http::URI(_protocol + "://" + element.AsString() + ":" + _port));
				}
			}
			if (!nodes.empty()) {
				std::lock_guard<std::mutex> guard(_nodes_mutex);
				_nodes = std::move(nodes);
				_node_idx = 0;
			}
		} else {
			throw std::runtime_error("Failed to fetch the list of live nodes");
		}
	}

	Aws::Http::URI NextNode() const {
		std::lock_guard<std::mutex> guard(_nodes_mutex);
		assert(!_nodes.empty());
		size_t idx = _node_idx;
		_node_idx = (_node_idx + 1) % _nodes.size();
		return _nodes[idx];
	}

	template<typename Duration>
	void StartNodeUpdater(Duration duration) {
		_keep_updating = true;
		_node_updater = std::unique_ptr<std::thread>(new std::thread([this, duration] {
			while (_keep_updating) {
				try {
					FetchLocalNodes();
					std::this_thread::sleep_for(duration);
				} catch (...) {
					// continue the thread anyway until it's explicitly stopped
				}
			}
		}));
	}

protected:
	// RandomNode() assumes that no concurrent updates to _nodes are performed.
	// _nodes should be locked with _nodes_mutex prior to calling this function.
	Aws::Http::URI RandomNode() const {
		assert(!_nodes_mutex.try_lock());
		static std::random_device rd;
		static std::mt19937 gen(rd());
		std::uniform_int_distribution<> dist(0, _nodes.size() - 1);
		return _nodes[dist(gen)];
	}
};
