package com.scylladb.alternator;

/**
 * Configuration class for Alternator load balancing settings. Contains datacenter and rack
 * configuration for filtering nodes.
 *
 * @author dmitry.kropachev
 * @since 1.0.5
 */
public class AlternatorConfig {
  private final String datacenter;
  private final String rack;

  /**
   * Package-private constructor. Use {@link AlternatorConfig} to create instances.
   *
   * @param datacenter the datacenter name
   * @param rack the rack name
   */
  protected AlternatorConfig(String datacenter, String rack) {
    this.datacenter = datacenter != null ? datacenter : "";
    this.rack = rack != null ? rack : "";
  }

  /**
   * Gets the configured datacenter.
   *
   * @return the datacenter name, or empty string if not set
   */
  public String getDatacenter() {
    return datacenter;
  }

  /**
   * Gets the configured rack.
   *
   * @return the rack name, or empty string if not set
   */
  public String getRack() {
    return rack;
  }

  /**
   * Creates a new builder for AlternatorConfig.
   *
   * @return a new {@link Builder}
   */
  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String datacenter = "";
    private String rack = "";

    /** Package-private constructor. Use {@link AlternatorConfig#builder()} to create instances. */
    Builder() {}

    /**
     * Sets the target datacenter. When specified, only nodes from this datacenter will be used for
     * load balancing. If not set, all nodes will be used.
     *
     * @param datacenter the datacenter name
     * @return this builder instance
     */
    public Builder withDatacenter(String datacenter) {
      this.datacenter = datacenter != null ? datacenter : "";
      return this;
    }

    /**
     * Sets the target rack. When specified along with a datacenter, only nodes from this rack will
     * be used for load balancing.
     *
     * @param rack the rack name
     * @return this builder instance
     */
    public Builder withRack(String rack) {
      this.rack = rack != null ? rack : "";
      return this;
    }

    /**
     * Builds and returns an {@link AlternatorConfig} instance with the configured settings.
     *
     * @return a new {@link AlternatorConfig} instance
     */
    public AlternatorConfig build() {
      return new AlternatorConfig(datacenter, rack);
    }
  }
}
