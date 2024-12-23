using Amazon.Runtime.Endpoints;

namespace ScyllaDB.Alternator
{
    public class EndpointProvider : IEndpointProvider
    {
        private readonly AlternatorLiveNodes _liveNodes;
        private static readonly NLog.Logger Logger = NLog.LogManager.GetCurrentClassLogger();

        public EndpointProvider(Uri seedUri, string datacenter, string rack)
        {
            _liveNodes = new AlternatorLiveNodes(seedUri, datacenter, rack);
            try
            {
                _liveNodes.Validate();
                _liveNodes.CheckIfRackAndDatacenterSetCorrectly();
                if (datacenter.Length != 0 || rack.Length != 0)
                {
                    if (!_liveNodes.CheckIfRackDatacenterFeatureIsSupported())
                    {
                        Logger.Error($"server {seedUri} does not support rack or datacenter filtering");
                    }
                }
            }
            catch (Exception e)
            {
                throw new SystemException("failed to start EndpointProvider", e);
            }

            _liveNodes.Start(CancellationToken.None);
        }

        public Endpoint ResolveEndpoint(EndpointParameters parameters)
        {
            return new Endpoint(_liveNodes.NextAsUri().ToString());
        }
    }
}