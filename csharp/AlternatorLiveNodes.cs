using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using System.Threading.Tasks;
using System.Diagnostics;
using Amazon.Runtime.Endpoints;

namespace ScyllaDB.Alternator
{
    public class AlternatorLiveNodes
    {
        private readonly string _alternatorScheme;
        private readonly int _alternatorPort;
        private List<Uri> _liveNodes;
        private readonly ReaderWriterLockSlim _liveNodesLock = new();
        private readonly List<Uri> _initialNodes;
        private int _nextLiveNodeIndex;
        private readonly string _rack;
        private readonly string _datacenter;
        private bool _started;

        private static readonly NLog.Logger Logger = NLog.LogManager.GetCurrentClassLogger();

        public AlternatorLiveNodes(Uri liveNode, string datacenter, string rack)
            : this([liveNode], liveNode.Scheme, liveNode.Port, datacenter, rack)
        {
        }

        public AlternatorLiveNodes(List<Uri> nodes, string scheme, int port, string datacenter, string rack)
        {
            if (nodes == null || nodes.Count == 0)
            {
                throw new SystemException("liveNodes cannot be null or empty");
            }

            _initialNodes = nodes;
            _alternatorScheme = scheme;
            _alternatorPort = port;
            _rack = rack;
            _datacenter = datacenter;
            _liveNodes = new List<Uri>();
            foreach (var node in _initialNodes)
            {
                _liveNodes.Add(node);
            }
        }

        public Task Start(CancellationToken cancellationToken)
        {
            if (_started)
            {
                return Task.CompletedTask;
            }

            Validate();

            Task.Run(() =>
            {
                UpdateCycle(cancellationToken);
                return Task.CompletedTask;
            }, cancellationToken);
            _started = true;
            return Task.CompletedTask;
        }

        private void UpdateCycle(CancellationToken cancellationToken)
        {
            Logger.Debug("AlternatorLiveNodes thread started");
            try
            {
                while (true)
                {
                    if (cancellationToken.IsCancellationRequested)
                    {
                        return;
                    }

                    try
                    {
                        UpdateLiveNodes();
                    }
                    catch (IOException e)
                    {
                        Logger.Error(e, "AlternatorLiveNodes failed to sync nodes list: %");
                    }

                    try
                    {
                        Thread.Sleep(1000);
                    }
                    catch (ThreadInterruptedException e)
                    {
                        Logger.Info("AlternatorLiveNodes thread interrupted and stopping");
                        return;
                    }
                }
            }
            finally
            {
                Logger.Info("AlternatorLiveNodes thread stopped");
            }
        }

        public class ValidationError : Exception
        {
            public ValidationError(string message) : base(message)
            {
            }

            public ValidationError(string message, Exception cause) : base(message, cause)
            {
            }
        }

        private void Validate()
        {
            try
            {
                // Make sure that `alternatorScheme` and `alternatorPort` are correct values
                HostToUri("1.1.1.1");
            }
            catch (UriFormatException e)
            {
                throw new ValidationError("failed to validate configuration", e);
            }
        }

        private Uri HostToUri(string host)
        {
            return new Uri($"{_alternatorScheme}://{host}:{_alternatorPort}");
        }

        private List<Uri> getLiveNodes()
        {
            _liveNodesLock.EnterReadLock();
            try
            {
                return _liveNodes.ToList();
            }
            finally
            {
                _liveNodesLock.ExitReadLock();
            }
        }

        private void setLiveNodes(List<Uri> nodes)
        {
            _liveNodesLock.EnterWriteLock();
            _liveNodes = nodes;
            _liveNodesLock.ExitWriteLock();
        }

        public Uri NextAsUri()
        {
            var nodes = getLiveNodes();
            if (nodes.Count == 0)
            {
                throw new InvalidOperationException("No live nodes available");
            }

            return nodes[Math.Abs(Interlocked.Increment(ref _nextLiveNodeIndex) % nodes.Count)];
        }

        private Uri NextAsUri(string path, string query)
        {
            Uri uri = NextAsUri();
            return new Uri($"{uri.Scheme}://{uri.Host}:{uri.Port}{path}?{query}");
        }

        private static string StreamToString(Stream stream)
        {
            using var reader = new StreamReader(stream);
            return reader.ReadToEnd();
        }

        private void UpdateLiveNodes()
        {
            var newHosts = GetNodes(NextAsLocalNodesUri());
            if (newHosts.Count == 0) return;
            setLiveNodes(newHosts);
            Logger.Info($"Updated hosts to {_liveNodes}");
        }

        private List<Uri> GetNodes(Uri uri)
        {
            using var client = new HttpClient();
            var response = client.GetAsync(uri).Result;
            if (!response.IsSuccessStatusCode)
            {
                return [];
            }

            var responseBody = StreamToString(response.Content.ReadAsStreamAsync().Result);
            // response looks like: ["127.0.0.2","127.0.0.3","127.0.0.1"]
            responseBody = responseBody.Trim();
            responseBody = responseBody.Substring(1, responseBody.Length - 2);
            var list = responseBody.Split(',');
            var newHosts = new List<Uri>();
            foreach (var host in list)
            {
                if (string.IsNullOrEmpty(host))
                {
                    continue;
                }

                var trimmedHost = host.Trim().Substring(1, host.Length - 2);
                try
                {
                    newHosts.Add(HostToUri(trimmedHost));
                }
                catch (UriFormatException e)
                {
                    Logger.Error(e, $"Invalid host: {trimmedHost}");
                }
            }

            return newHosts;
        }

        private Uri NextAsLocalNodesUri()
        {
            if (string.IsNullOrEmpty(_rack) && string.IsNullOrEmpty(_datacenter))
            {
                return NextAsUri("/localnodes", null);
            }

            var query = "";
            if (!string.IsNullOrEmpty(_rack))
            {
                query = "rack=" + _rack;
            }

            if (string.IsNullOrEmpty(_datacenter)) return NextAsUri("/localnodes", query);
            if (string.IsNullOrEmpty(query))
            {
                query = $"dc={_datacenter}";
            }
            else
            {
                query += $"&dc={_datacenter}";
            }

            return NextAsUri("/localnodes", query);
        }

        public class FailedToCheck : Exception
        {
            public FailedToCheck(string message, Exception cause) : base(message, cause)
            {
            }

            public FailedToCheck(string message) : base(message)
            {
            }
        }

        public void CheckIfRackAndDatacenterSetCorrectly()
        {
            if (string.IsNullOrEmpty(_rack) && string.IsNullOrEmpty(_datacenter))
            {
                return;
            }

            try
            {
                var nodes = GetNodes(NextAsLocalNodesUri());
                if (nodes.Count == 0)
                {
                    throw new ValidationError("node returned empty list, datacenter or rack are set incorrectly");
                }
            }
            catch (IOException e)
            {
                throw new FailedToCheck("failed to read list of nodes from the node", e);
            }
        }

        public bool CheckIfRackDatacenterFeatureIsSupported()
        {
            var uri = NextAsUri("/localnodes", null);
            Uri fakeRackUrl;
            try
            {
                fakeRackUrl = new Uri($"{uri.Scheme}://{uri.Host}:{uri.Port}{uri.Query}&rack=fakeRack");
            }
            catch (UriFormatException e)
            {
                // Should not ever happen
                throw new FailedToCheck("Invalid Uri: " + uri, e);
            }

            try
            {
                var hostsWithFakeRack = GetNodes(fakeRackUrl);
                var hostsWithoutRack = GetNodes(uri);
                if (hostsWithoutRack.Count == 0)
                {
                    // This should not normally happen.
                    // If list of nodes is empty, it is impossible to conclude if it supports rack/datacenter filtering or not.
                    throw new FailedToCheck($"host {uri} returned empty list");
                }

                // When rack filtering is not supported server returns same nodes.
                return hostsWithFakeRack.Count != hostsWithoutRack.Count;
            }
            catch (IOException e)
            {
                throw new FailedToCheck("failed to read list of nodes from the node", e);
            }
        }
    }
}