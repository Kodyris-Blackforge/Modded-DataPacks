package net.kodyris.mdp.script;

import java.util.*;

public final class MdpRegistry {
    private final Map<String, MdpFile> files = new LinkedHashMap<>();
    private final Map<String, List<MdpBlock>> events = new HashMap<>();
    private final Map<String, MdpBlock> funcs = new LinkedHashMap<>();

    public void add(MdpFile f) {
        files.put(f.id(), f);
        if (f.disabledReason() != null) return;      // header error: nothing from this file registers
        for (MdpBlock b : f.blocks()) {
            if (b.kind() == BlockKind.EVT) {
                events.computeIfAbsent(b.name(), k -> new ArrayList<>()).add(b);
            } else {
                MdpBlock prev = funcs.putIfAbsent(b.key(), b);
                if (prev != null) b.disable("duplicate of function at line " + prev.line());
            }
        }
    }

    public Map<String, MdpFile> files() { return files; }
    public MdpFile file(String id) { return files.get(id); }
    public MdpBlock func(String key) { return funcs.get(key); }
    public Collection<MdpBlock> funcs() { return funcs.values(); }
    /** Active blocks with nothing in them (a body of only blank/comment lines counts). Off or broken files are skipped. */
    public List<MdpBlock> emptyBlocks() {
        List<MdpBlock> out = new ArrayList<>();
        for (MdpFile f : files.values()) {
            if (!f.usable()) continue;
            for (MdpBlock b : f.blocks()) if (b.isActive() && b.body().isEmpty()) out.add(b);
        }
        return out;
    }

    /** .json list files used by conditions in usable files, with the active blocks that use each. */
    public Map<String, List<MdpBlock>> referencedLists() {
        Map<String, List<MdpBlock>> out = new LinkedHashMap<>();
        for (MdpFile f : files.values()) {
            if (!f.usable()) continue;
            for (MdpBlock b : f.blocks())
                if (b.isActive()) for (String p : b.listPaths()) out.computeIfAbsent(p, k -> new ArrayList<>()).add(b);
        }
        return out;
    }

    public List<MdpBlock> eventBlocks(String event) { return events.getOrDefault(event, List.of()); }
}
