package com.rottenapple.module;

import com.rottenapple.bridge.MC;
import java.util.List;

/** List of item storage ids (mirrors Raven's ItemListSetting). */
public class ItemListSetting extends BlockListSetting {
    public ItemListSetting(String name) {
        super(name);
    }

    public ItemListSetting(String name, String... legacyProfileKeys) {
        super(name, legacyProfileKeys);
    }

    public ItemListSetting(GroupSetting group, String name) {
        super(group, name);
    }

    public ItemListSetting(GroupSetting group, String name, String... legacyProfileKeys) {
        super(group, name, legacyProfileKeys);
    }

    public void addItem(String storageId) {
        addBlock(storageId);
    }

    public void removeItem(String storageId) {
        removeBlock(storageId);
    }

    public List<String> getItems() {
        return getBlocks();
    }

    public boolean containsItem(String storageId) {
        return contains(storageId);
    }

    public boolean matches(Object stack) {
        return MC.matchItemList(getItems(), stack);
    }
}
