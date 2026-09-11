package com.rottenapple.module;

import java.util.ArrayList;
import java.util.List;

/** List of block registry ids, supports "mod:id:*" wildcards (mirrors Raven). */
public class BlockListSetting extends Setting {
    private final List<String> blocks = new ArrayList<>();
    public GroupSetting group;

    public BlockListSetting(String name) {
        super(name);
    }

    public BlockListSetting(String name, String... legacyProfileKeys) {
        super(name);
    }

    public BlockListSetting(GroupSetting group, String name) {
        super(name);
        this.group = group;
    }

    public BlockListSetting(GroupSetting group, String name, String... legacyProfileKeys) {
        super(name);
        this.group = group;
    }

    public void addBlock(String registryName) {
        if (!blocks.contains(registryName)) {
            blocks.add(registryName);
        }
    }

    public void removeBlock(String registryName) {
        blocks.remove(registryName);
    }

    public List<String> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<String> ids) {
        blocks.clear();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isEmpty() && !blocks.contains(id)) {
                    blocks.add(id);
                }
            }
        }
    }

    public boolean contains(String storageId) {
        if (blocks.contains(storageId)) return true;
        String registryId = extractRegistryId(storageId);
        return registryId != null && blocks.contains(registryId + ":*");
    }

    @Override
    public String getProfileKey() {
        return group == null ? getName() : group.getName() + "." + getName();
    }

    private static String extractRegistryId(String storageId) {
        if (storageId == null || storageId.isEmpty()) return null;
        if (storageId.endsWith(":*")) return storageId.substring(0, storageId.length() - 2);
        String[] p = storageId.split(":");
        if (p.length >= 3) return p[0] + ":" + p[1];
        if (p.length == 2) return storageId;
        return null;
    }
}
