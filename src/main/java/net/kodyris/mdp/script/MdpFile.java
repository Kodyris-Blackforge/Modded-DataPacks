package net.kodyris.mdp.script;

import java.util.ArrayList;
import java.util.List;

public final class MdpFile {
    private final String id;
    private FileConfig config;
    private String disabled;
    private final List<MdpBlock> blocks = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public MdpFile(String id) { this.id = id; }

    public String id() { return id; }
    public FileConfig config() { return config; }
    public String disabledReason() { return disabled; }
    public List<MdpBlock> blocks() { return blocks; }
    public List<String> warnings() { return warnings; }

    /** Header OK and cfg.Enabled = true. */
    public boolean usable() { return disabled == null && config != null && config.enabled(); }

    void setConfig(FileConfig c) { this.config = c; }
    public void disable(String reason) { if (disabled == null) disabled = reason; }
}
