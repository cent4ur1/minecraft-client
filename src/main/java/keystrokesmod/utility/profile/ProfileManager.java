package keystrokesmod.utility.profile;

import com.google.gson.*;
import keystrokesmod.Raven;
import keystrokesmod.clickgui.ClickGui;
import keystrokesmod.clickgui.components.impl.CategoryComponent;
import keystrokesmod.event.PostProfileLoadEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.client.Gui;
import keystrokesmod.module.impl.client.Relationships;
import keystrokesmod.module.impl.minigames.BedWars;
import keystrokesmod.module.impl.minigames.SkyWars;
import keystrokesmod.module.impl.player.FastPlace;
import keystrokesmod.module.impl.player.HideWindow;
import keystrokesmod.module.impl.render.HUD;
import keystrokesmod.module.impl.render.PotionHUD;
import keystrokesmod.module.impl.render.Radar;
import keystrokesmod.module.impl.render.TargetHUD;
import keystrokesmod.module.setting.Setting;
import keystrokesmod.module.setting.impl.BlockListSetting;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.KeySetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.module.setting.impl.StringListSetting;
import keystrokesmod.module.setting.impl.TextSetting;
import keystrokesmod.script.Manager;
import keystrokesmod.utility.IMinecraftInstance;
import keystrokesmod.utility.Utils;
import net.minecraftforge.common.MinecraftForge;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ProfileManager implements IMinecraftInstance {
    private static final String DEFAULT_PROFILE_NAME = "default";
    private static final char[] INVALID_PROFILE_NAME_CHARS = new char[]{'\\', '/', ':', '*', '?', '"', '<', '>', '|'};
    private static final String CONFIG_DIR = mc.mcDataDir + File.separator + "keystrokes" + File.separator + "settings.txt";
    private static final String SEPARATOR = ":";
    private static final String SEPARATOR_FULL = SEPARATOR + " ";
    private static final String DEFAULT_PROFILE_KEY = "default-profile";

    private static final class SavedCategoryState {
        final float x, y;
        final boolean opened;

        SavedCategoryState(float x, float y, boolean opened) {
            this.x = x;
            this.y = y;
            this.opened = opened;
        }
    }

    private static final class RequestedModuleState {
        boolean enabled;
        int keybind;
        Boolean hidden;

        RequestedModuleState(boolean enabled, int keybind) {
            this.enabled = enabled;
            this.keybind = keybind;
        }
    }

    public File directory;
    public List<Profile> profiles = new ArrayList<>();

    public ProfileManager() {
        directory = new File(mc.mcDataDir + File.separator + "keystrokes", "profiles");
        if (!directory.exists()) {
            boolean success = directory.mkdirs();
            if (!success) {
                System.out.println("There was an issue creating profiles directory.");
                return;
            }
        }
        if (getProfileFiles().isEmpty()) {
            saveProfile(new Profile(DEFAULT_PROFILE_NAME, 0));
        }
    }

    public void saveProfile(Profile profile) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("keybind", profile.getModule().getKeycode());
        JsonArray jsonArray = new JsonArray();
        for (Module module : Raven.moduleManager.getModules()) {
            if (module.ignoreOnSave && !shouldSaveModuleStateOnly(module)) {
                continue;
            }
            JsonObject moduleInformation = module.ignoreOnSave ? getModuleStateObject(module) : getJsonObject(module);
            jsonArray.add(moduleInformation);
        }
        if (Raven.scriptManager != null && Raven.scriptManager.scripts != null) {
            for (Module module : Raven.scriptManager.scripts.values()) {
                if (module.ignoreOnSave) {
                    continue;
                }
                JsonObject moduleInformation = getJsonObject(module);
                jsonArray.add(moduleInformation);
            }
        }
        jsonObject.add("modules", jsonArray);
        try (FileWriter fileWriter = new FileWriter(new File(directory, profile.getName() + ".json"))) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(jsonObject, fileWriter);
        } catch (Exception e) {
            failedMessage("save", profile.getName());
            e.printStackTrace();
        }
    }

    public Profile createProfile(String requestedName, int bind) {
        String profileName = normalizeProfileName(requestedName);
        String validationError = validateProfileName(profileName, null);
        if (validationError != null) {
            Utils.sendMessage("&c" + validationError);
            return null;
        }

        Profile profile = new Profile(profileName, bind);
        saveProfile(profile);
        profiles.add(profile);
        refreshProfileModules();
        return profile;
    }

    private static JsonObject getJsonObject(Module module) {
        JsonObject moduleInformation = new JsonObject();
        moduleInformation.addProperty("name", (module.moduleCategory() == Module.category.scripts && !(module instanceof Manager)) ?  "sc-" + module.getName() :  module.getName());
        if (module.canBeEnabled) {
            moduleInformation.addProperty("enabled", module.isEnabled());
            moduleInformation.addProperty("hidden", module.isHidden());
            moduleInformation.addProperty("keybind", module.getKeycode());
        }
        if (module instanceof HUD) {
            moduleInformation.addProperty("posX", HUD.posX);
            moduleInformation.addProperty("posY", HUD.posY);
            moduleInformation.addProperty("relPosX", HUD.getRelativePosX());
            moduleInformation.addProperty("relPosY", HUD.getRelativePosY());
        }
        else if (module instanceof TargetHUD) {
            moduleInformation.addProperty("posX", ModuleManager.targetHUD.posX);
            moduleInformation.addProperty("posY", ModuleManager.targetHUD.posY);
        }
        else if (module instanceof PotionHUD) {
            PotionHUD potionHUD = (PotionHUD) module;
            moduleInformation.addProperty("posX", potionHUD.getPosX());
            moduleInformation.addProperty("posY", potionHUD.getPosY());
            moduleInformation.addProperty("relPosX", potionHUD.getRelativePosX());
            moduleInformation.addProperty("relPosY", potionHUD.getRelativePosY());
        }
        else if (module instanceof Radar) {
            Radar radar = (Radar) module;
            moduleInformation.addProperty("posX", radar.getPosX());
            moduleInformation.addProperty("posY", radar.getPosY());
            moduleInformation.addProperty("relPosX", radar.getRelativePosX());
            moduleInformation.addProperty("relPosY", radar.getRelativePosY());
        }
        else if (module instanceof HideWindow) {
            HideWindow hw = (HideWindow) module;
            moduleInformation.addProperty("posX", hw.getPosX());
            moduleInformation.addProperty("posY", hw.getPosY());
            moduleInformation.addProperty("relPosX", hw.getRelativePosX());
            moduleInformation.addProperty("relPosY", hw.getRelativePosY());
        }
        else if (module instanceof FastPlace) {
            FastPlace fp = (FastPlace) module;
            moduleInformation.addProperty("posX", fp.getPosX());
            moduleInformation.addProperty("posY", fp.getPosY());
            moduleInformation.addProperty("relPosX", fp.getRelativePosX());
            moduleInformation.addProperty("relPosY", fp.getRelativePosY());
        }
        else if (module instanceof BedWars) {
            BedWars bedWars = (BedWars) module;
            moduleInformation.addProperty("closestEnemyPosX", bedWars.getClosestEnemyPosX());
            moduleInformation.addProperty("closestEnemyPosY", bedWars.getClosestEnemyPosY());
            moduleInformation.addProperty("closestEnemyRelPosX", bedWars.getClosestEnemyRelativePosX());
            moduleInformation.addProperty("closestEnemyRelPosY", bedWars.getClosestEnemyRelativePosY());
            moduleInformation.addProperty("magicMilkPosX", bedWars.getMagicMilkPosX());
            moduleInformation.addProperty("magicMilkPosY", bedWars.getMagicMilkPosY());
            moduleInformation.addProperty("magicMilkRelPosX", bedWars.getMagicMilkRelativePosX());
            moduleInformation.addProperty("magicMilkRelPosY", bedWars.getMagicMilkRelativePosY());
        }
        else if (module instanceof SkyWars) {
            SkyWars skyWars = (SkyWars) module;
            moduleInformation.addProperty("closestEnemyPosX", skyWars.getClosestEnemyPosX());
            moduleInformation.addProperty("closestEnemyPosY", skyWars.getClosestEnemyPosY());
            moduleInformation.addProperty("closestEnemyRelPosX", skyWars.getClosestEnemyRelativePosX());
            moduleInformation.addProperty("closestEnemyRelPosY", skyWars.getClosestEnemyRelativePosY());
        }
        else if (module instanceof Gui) {
            for (CategoryComponent c : ClickGui.categories) {
                moduleInformation.addProperty(c.category.name(), c.x + "," + c.y + "," + c.opened);
            }
        }
        for (Setting setting : module.getSettings()) {
            if (setting instanceof ButtonSetting && !((ButtonSetting) setting).isMethodButton) {
                moduleInformation.addProperty(setting.getProfileKey(), ((ButtonSetting) setting).isToggled());
            }
            else if (setting instanceof SliderSetting) {
                SliderSetting sliderSetting = (SliderSetting) setting;
                if (sliderSetting.isString && sliderSetting.canBeDisabled && sliderSetting.getInput() == -1) {
                    moduleInformation.addProperty(setting.getProfileKey(), -1);
                }
                else if (sliderSetting.isString) {
                    moduleInformation.addProperty(setting.getProfileKey(), sliderSetting.getSelectedOption());
                }
                else {
                    moduleInformation.addProperty(setting.getProfileKey(), sliderSetting.getInput());
                }
            }
            else if (setting instanceof KeySetting) {
                moduleInformation.addProperty(setting.getProfileKey(), ((KeySetting) setting).getKey());
            }
            else if (setting instanceof TextSetting) {
                moduleInformation.addProperty(setting.getProfileKey(), ((TextSetting) setting).getText());
            }
            else if (setting instanceof ColorSetting) {
                ColorSetting cs = (ColorSetting) setting;
                moduleInformation.addProperty(setting.getProfileKey(),
                        cs.getRed() + "," + cs.getGreen() + "," + cs.getBlue() + "," + cs.getAlpha());
            }
            else if (setting instanceof BlockListSetting) {
                moduleInformation.add(setting.getProfileKey(), ((BlockListSetting) setting).toJsonArray());
            }
            else if (setting instanceof StringListSetting) {
                moduleInformation.add(setting.getProfileKey(), ((StringListSetting) setting).toJsonArray());
            }
        }
        return moduleInformation;
    }

    private static JsonObject getModuleStateObject(Module module) {
        JsonObject moduleInformation = new JsonObject();
        moduleInformation.addProperty("name", module.getName());
        if (module.canBeEnabled) {
            moduleInformation.addProperty("enabled", module.isEnabled());
            moduleInformation.addProperty("hidden", module.isHidden());
            moduleInformation.addProperty("keybind", module.getKeycode());
        }
        return moduleInformation;
    }

    private static boolean shouldSaveModuleStateOnly(Module module) {
        return module instanceof Relationships;
    }

    public void loadProfile(String name) {
        Profile existingProfile = getProfile(name);
        String profileName = existingProfile != null ? existingProfile.getName() : normalizeProfileName(name);
        boolean foundProfile = false;
        for (File file : getProfileFiles()) {
            if (!file.exists()) {
                failedMessage("load", profileName);
                System.out.println("Failed to load " + profileName);
                return;
            }
            if (!file.getName().equals(profileName + ".json")) {
                continue;
            }
            try (FileReader fileReader = new FileReader(file)) {
                JsonParser jsonParser = new JsonParser();
                JsonObject profileJson = jsonParser.parse(fileReader).getAsJsonObject();
                if (profileJson == null) {
                    failedMessage("load", profileName);
                    return;
                }
                JsonArray modules = profileJson.getAsJsonArray("modules");
                if (modules == null) {
                    failedMessage("load", profileName);
                    return;
                }
                List<Module> loadableModules = getLoadableModules();
                Map<Module, RequestedModuleState> requestedModuleStates = createDefaultRequestedModuleStates(loadableModules);
                Map<Module, JsonObject> loadedModuleData = new LinkedHashMap<Module, JsonObject>();
                boolean loadedRelationshipsState = false;
                Map<String, SavedCategoryState> savedGuiCategoryState = new HashMap<>();
                for (JsonElement moduleJson : modules) {
                    JsonObject moduleInformation = moduleJson.getAsJsonObject();
                    String moduleName = moduleInformation.get("name").getAsString();

                    if (moduleName == null || moduleName.isEmpty()) {
                        continue;
                    }

                    Module module = Raven.moduleManager.getModule(moduleName);
                    if (module == null && moduleName.startsWith("sc-") && Raven.scriptManager != null) {
                        for (Module module1 : Raven.scriptManager.scripts.values()) {
                            if (module1.getName().equals(moduleName.substring(3))) {
                                module = module1;
                            }
                        }
                    }

                    if (module == null) {
                        continue;
                    }

                    loadedModuleData.put(module, moduleInformation);

                    if (module instanceof Relationships) {
                        loadedRelationshipsState = true;
                    }

                    if (module.canBeEnabled()) {
                        RequestedModuleState requestedState = requestedModuleStates.get(module);
                        if (requestedState == null) {
                            requestedState = new RequestedModuleState(false, 0);
                            requestedModuleStates.put(module, requestedState);
                        }
                        if (moduleInformation.has("enabled")) {
                            requestedState.enabled = moduleInformation.get("enabled").getAsBoolean();
                        }
                        if (moduleInformation.has("hidden")) {
                            requestedState.hidden = moduleInformation.get("hidden").getAsBoolean();
                        }
                        if (moduleInformation.has("keybind")) {
                            requestedState.keybind = moduleInformation.get("keybind").getAsInt();
                        }
                    }
                }
                if (!loadedRelationshipsState && ModuleManager.relationships != null && Raven.playerRelationsManager != null) {
                    RequestedModuleState relationshipsState = requestedModuleStates.get(ModuleManager.relationships);
                    if (relationshipsState != null) {
                        relationshipsState.enabled = Raven.playerRelationsManager.isActive();
                    }
                }
                for (Module module : loadableModules) {
                    RequestedModuleState requestedState = requestedModuleStates.get(module);
                    if (requestedState == null) {
                        continue;
                    }

                    if (!requestedState.enabled && module.isEnabled()) {
                        module.disable();
                    }
                }

                for (Module module : loadableModules) {
                    RequestedModuleState requestedState = requestedModuleStates.get(module);
                    if (requestedState == null) {
                        continue;
                    }

                    module.setBind(requestedState.keybind);
                    if (requestedState.hidden != null) {
                        module.setHidden(requestedState.hidden);
                    }
                }

                for (Map.Entry<Module, JsonObject> entry : loadedModuleData.entrySet()) {
                    Module module = entry.getKey();
                    JsonObject moduleInformation = entry.getValue();

                    if (module.getName().equals("HUD")) {
                        if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                            HUD.setRelativePosition(
                                    moduleInformation.get("relPosX").getAsFloat(),
                                    moduleInformation.get("relPosY").getAsFloat()
                            );
                        }
                        else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                            float hudX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : HUD.posX;
                            float hudY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : HUD.posY;
                            HUD.setAbsolutePosition(hudX, hudY);
                        }
                    }
                    else if (module.getName().equals("TargetHUD")) {
                        if (moduleInformation.has("posX")) {
                            int posX = moduleInformation.get("posX").getAsInt();
                            ModuleManager.targetHUD.posX = posX;
                        }
                        if (moduleInformation.has("posY")) {
                            int posY = moduleInformation.get("posY").getAsInt();
                            ModuleManager.targetHUD.posY = posY;
                        }
                    }
                    else if (module.getName().equals("Potion HUD")) {
                        PotionHUD potionHUD = (PotionHUD) module;
                        if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                            potionHUD.setRelativePosition(
                                    moduleInformation.get("relPosX").getAsFloat(),
                                    moduleInformation.get("relPosY").getAsFloat()
                            );
                        }
                        else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                            float posX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : potionHUD.getPosX();
                            float posY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : potionHUD.getPosY();
                            potionHUD.setAbsolutePosition(posX, posY);
                        }
                    }
                    else if (module.getName().equals("Radar")) {
                        Radar radar = (Radar) module;
                        if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                            radar.setRelativePosition(
                                    moduleInformation.get("relPosX").getAsFloat(),
                                    moduleInformation.get("relPosY").getAsFloat()
                            );
                        }
                        else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                            float posX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : radar.getPosX();
                            float posY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : radar.getPosY();
                            radar.setAbsolutePosition(posX, posY);
                        }
                    }
                    else if (module.getName().equals("Hide Window")) {
                        HideWindow hw = (HideWindow) module;
                        if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                            hw.setRelativePosition(
                                    moduleInformation.get("relPosX").getAsFloat(),
                                    moduleInformation.get("relPosY").getAsFloat()
                            );
                        }
                        else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                            float posX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : hw.getPosX();
                            float posY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : hw.getPosY();
                            hw.setAbsolutePosition(posX, posY);
                        }
                    }
                    else if (module.getName().equals("Fast Place")) {
                        FastPlace fp = (FastPlace) module;
                        if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                            fp.setRelativePosition(
                                    moduleInformation.get("relPosX").getAsFloat(),
                                    moduleInformation.get("relPosY").getAsFloat()
                            );
                        }
                        else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                            float posX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : fp.getPosX();
                            float posY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : fp.getPosY();
                            fp.setAbsolutePosition(posX, posY);
                        }
                    }
                    else if (module instanceof BedWars) {
                        BedWars bedWars = (BedWars) module;
                        if (moduleInformation.has("closestEnemyRelPosX") && moduleInformation.has("closestEnemyRelPosY")) {
                            bedWars.setClosestEnemyRelativePosition(
                                    moduleInformation.get("closestEnemyRelPosX").getAsFloat(),
                                    moduleInformation.get("closestEnemyRelPosY").getAsFloat()
                            );
                        }
                        else if (moduleInformation.has("closestEnemyPosX") || moduleInformation.has("closestEnemyPosY")) {
                            float posX = moduleInformation.has("closestEnemyPosX")
                                    ? moduleInformation.get("closestEnemyPosX").getAsFloat()
                                    : bedWars.getClosestEnemyPosX();
                            float posY = moduleInformation.has("closestEnemyPosY")
                                    ? moduleInformation.get("closestEnemyPosY").getAsFloat()
                                    : bedWars.getClosestEnemyPosY();
                            bedWars.setClosestEnemyAbsolutePosition(posX, posY);
                        }

                        if (moduleInformation.has("magicMilkRelPosX") && moduleInformation.has("magicMilkRelPosY")) {
                            bedWars.setMagicMilkRelativePosition(
                                    moduleInformation.get("magicMilkRelPosX").getAsFloat(),
                                    moduleInformation.get("magicMilkRelPosY").getAsFloat()
                            );
                        }
                        else if (moduleInformation.has("magicMilkPosX") || moduleInformation.has("magicMilkPosY")) {
                            float posX = moduleInformation.has("magicMilkPosX")
                                    ? moduleInformation.get("magicMilkPosX").getAsFloat()
                                    : bedWars.getMagicMilkPosX();
                            float posY = moduleInformation.has("magicMilkPosY")
                                    ? moduleInformation.get("magicMilkPosY").getAsFloat()
                                    : bedWars.getMagicMilkPosY();
                            bedWars.setMagicMilkAbsolutePosition(posX, posY);
                        }
                    }
                    else if (module instanceof SkyWars) {
                        SkyWars skyWars = (SkyWars) module;
                        if (moduleInformation.has("closestEnemyRelPosX") && moduleInformation.has("closestEnemyRelPosY")) {
                            skyWars.setClosestEnemyRelativePosition(
                                    moduleInformation.get("closestEnemyRelPosX").getAsFloat(),
                                    moduleInformation.get("closestEnemyRelPosY").getAsFloat()
                            );
                        }
                        else if (moduleInformation.has("closestEnemyPosX") || moduleInformation.has("closestEnemyPosY")) {
                            float posX = moduleInformation.has("closestEnemyPosX")
                                    ? moduleInformation.get("closestEnemyPosX").getAsFloat()
                                    : skyWars.getClosestEnemyPosX();
                            float posY = moduleInformation.has("closestEnemyPosY")
                                    ? moduleInformation.get("closestEnemyPosY").getAsFloat()
                                    : skyWars.getClosestEnemyPosY();
                            skyWars.setClosestEnemyAbsolutePosition(posX, posY);
                        }
                    }
                    else if (module.getName().equals("Gui")) {
                        for (Map.Entry<String, JsonElement> setting : moduleInformation.entrySet()) {
                            String settingName = setting.getKey();
                            if (!Module.categoriesString.contains(settingName)) {
                                continue;
                            }
                            String element = setting.getValue().getAsString();
                            String[] statesStr = element.split(",");

                            float posX = Float.parseFloat(statesStr[0]);
                            float posY = Float.parseFloat(statesStr[1]);
                            boolean opened = statesStr.length > 2 && Boolean.parseBoolean(statesStr[2]);
                            savedGuiCategoryState.put(settingName, new SavedCategoryState(posX, posY, opened));
                        }
                    }

                    for (Setting setting : module.getSettings()) {
                        setting.loadProfile(moduleInformation);
                    }
                }

                for (Module module : loadableModules) {
                    RequestedModuleState requestedState = requestedModuleStates.get(module);
                    if (requestedState == null) {
                        continue;
                    }

                    if (requestedState.enabled && !module.isEnabled()) {
                        module.enable();
                    }
                }

                Raven.currentProfile = getProfile(profileName);

                boolean loadGuiPositions = Gui.loadGuiPositions.isToggled();
                Raven.clickGui.refreshAfterProfileLoad();
                if (loadGuiPositions) {
                    for (CategoryComponent c : ClickGui.categories) {
                        SavedCategoryState state = savedGuiCategoryState.get(c.category.name());
                        if (state != null) {
                            c.applySavedState(state.x, state.y, state.opened, true);
                        }
                    }
                }
                MinecraftForge.EVENT_BUS.post(new PostProfileLoadEvent(Raven.currentProfile.getName()));
                return;
            }
            catch (Exception e) {
                failedMessage("load", profileName);
                e.printStackTrace();
                return;
            }
        }
        if (!foundProfile) {
            failedMessage("load", profileName);
        }
    }

    private List<Module> getLoadableModules() {
        List<Module> loadableModules = new ArrayList<Module>(Raven.getModuleManager().getModules());
        if (Raven.scriptManager != null && Raven.scriptManager.scripts != null) {
            loadableModules.addAll(Raven.scriptManager.scripts.values());
        }
        return loadableModules;
    }

    private Map<Module, RequestedModuleState> createDefaultRequestedModuleStates(List<Module> modules) {
        Map<Module, RequestedModuleState> requestedModuleStates = new HashMap<Module, RequestedModuleState>();
        for (Module module : modules) {
            if (module.canBeEnabled()) {
                requestedModuleStates.put(module, new RequestedModuleState(false, 0));
            }
        }
        return requestedModuleStates;
    }

    public boolean deleteProfile(String name) {
        Profile removedProfile = getProfile(name);
        String profileName = removedProfile != null ? removedProfile.getName() : normalizeProfileName(name);
        File profileFile = new File(directory, profileName + ".json");

        if (profileFile.exists() && !(profileFile.delete() || !profileFile.exists())) {
            return false;
        }

        boolean wasCurrentProfile = removedProfile != null && Raven.currentProfile == removedProfile;
        if (isDefaultProfile(profileName)) {
            removeDefaultProfile();
        }
        if (removedProfile != null) {
            profiles.remove(removedProfile);
        }
        if (wasCurrentProfile) {
            Raven.currentProfile = null;
        }

        if (profiles.isEmpty()) {
            Profile fallbackProfile = createProfile(DEFAULT_PROFILE_NAME, 0);
            if (fallbackProfile == null) {
                return false;
            }
        }
        else {
            refreshProfileModules();
            if (wasCurrentProfile) {
                Profile fallbackProfile = getDefaultOrFirstProfile();
                if (fallbackProfile != null) {
                    loadProfile(fallbackProfile.getName());
                }
            }
        }
        return removedProfile != null;
    }

    public void loadProfiles(boolean sendMessage) {
        String currentProfileName = Raven.currentProfile != null ? Raven.currentProfile.getName() : null;
        boolean currentProfileSaved = Raven.currentProfile == null || Raven.currentProfile.getModule().saved;
        profiles.clear();
        if (!directory.exists() && !directory.mkdirs()) {
            Utils.sendMessage("&cFailed to load profiles.");
            return;
        }

        List<File> profileFiles = getProfileFiles();
        if (profileFiles.isEmpty()) {
            saveProfile(new Profile(DEFAULT_PROFILE_NAME, 0));
            profileFiles = getProfileFiles();
        }

        for (File file : profileFiles) {
            try (FileReader fileReader = new FileReader(file)) {
                JsonParser jsonParser = new JsonParser();
                JsonObject profileJson = jsonParser.parse(fileReader).getAsJsonObject();
                String profileName = file.getName().replace(".json", "");

                if (profileJson == null) {
                    failedMessage("load", profileName);
                    return;
                }

                int keybind = 0;

                if (profileJson.has("keybind")) {
                    keybind = profileJson.get("keybind").getAsInt();
                }

                Profile profile = new Profile(profileName, keybind);
                profiles.add(profile);
            } catch (Exception e) {
                Utils.sendMessage("&cFailed to load profiles.");
                e.printStackTrace();
            }
        }

        if (currentProfileName != null) {
            Raven.currentProfile = getProfile(currentProfileName);
            if (Raven.currentProfile != null) {
                Raven.currentProfile.getModule().saved = currentProfileSaved;
            }
        }
        refreshProfileModules();
        if (sendMessage) {
            Utils.sendMessage("&b" + profileFiles.size() + " &7profiles loaded.");
        }
        if (getDefaultProfile() != null) {
            this.loadProfile(getDefaultProfile().getName());
            Raven.currentProfile = getDefaultProfile();
        }
    }

    public List<File> getProfileFiles() {
        List<File> profileFiles = new ArrayList<>();
        if (directory.exists()) {
            File[] files = directory.listFiles();
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".json")) {
                    continue;
                }
                profileFiles.add(file);
            }
        }
        return profileFiles;
    }

    public Profile getProfile(String name) {
        for (Profile profile : profiles) {
            if (profile.getName().equalsIgnoreCase(name)) {
                return profile;
            }
        }
        return null;
    }

    public void loadInitialProfile() {
        Profile defaultProfile = getDefaultProfile();
        if (defaultProfile != null) {
            loadProfile(defaultProfile.getName());
        }
        else {
            Raven.currentProfile = null;
        }
    }

    public void failedMessage(String reason, String name) {
        Utils.sendMessage("&cFailed to " + reason + ": &b" + name);
    }

    public boolean renameProfile(Profile profile, String requestedName) {
        if (profile == null) {
            Utils.sendMessage("&cFailed to rename profile.");
            return false;
        }

        String oldName = profile.getName();
        String newName = normalizeProfileName(requestedName);
        String validationError = validateProfileName(newName, oldName);
        if (validationError != null) {
            Utils.sendMessage("&c" + validationError);
            return false;
        }

        if (oldName.equals(newName)) {
            profile.setName(newName);
            return true;
        }

        File oldFile = new File(directory, oldName + ".json");
        File newFile = new File(directory, newName + ".json");

        if (!oldFile.exists()) {
            failedMessage("rename", oldName);
            return false;
        }

        boolean wasDefault = isDefaultProfile(oldName);
        try {
            Files.move(oldFile.toPath(), newFile.toPath());
            profile.setName(newName);
            if (wasDefault) {
                setDefaultProfile(newName);
            }
            return true;
        }
        catch (Exception e) {
            failedMessage("rename", oldName);
            e.printStackTrace();
            return false;
        }
    }

    private void refreshProfileModules() {
        if (Raven.clickGui == null || Raven.clickGui.categories == null) {
            return;
        }
        for (CategoryComponent categoryComponent : Raven.clickGui.categories) {
            if (categoryComponent.category == Module.category.profiles) {
                categoryComponent.reloadModules(true);
                break;
            }
        }
    }

    private Profile getDefaultOrFirstProfile() {
        Profile defaultProfile = getDefaultProfile();
        if (defaultProfile != null) {
            return defaultProfile;
        }
        Profile fallback = getProfile(DEFAULT_PROFILE_NAME);
        if (fallback != null) {
            return fallback;
        }
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public String getDefaultProfileName() {
        return retrieveSetting(DEFAULT_PROFILE_KEY);
    }

    public Profile getDefaultProfile() {
        String defaultName = getDefaultProfileName();
        if (defaultName == null || defaultName.isEmpty()) {
            return null;
        }
        return getProfile(defaultName);
    }

    public boolean isDefaultProfile(Profile profile) {
        return profile != null && isDefaultProfile(profile.getName());
    }

    public boolean isDefaultProfile(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String defaultName = getDefaultProfileName();
        return defaultName != null && defaultName.equalsIgnoreCase(name);
    }

    public void setDefaultProfile(Profile profile) {
        if (profile != null) {
            setDefaultProfile(profile.getName());
        }
    }

    public void setDefaultProfile(String profileName) {
        if (profileName != null && !profileName.isEmpty()) {
            setSetting(DEFAULT_PROFILE_KEY, profileName);
        }
    }

    public void removeDefaultProfile() {
        setSetting(DEFAULT_PROFILE_KEY, null);
    }

    private void ensureConfigFileExists() throws IOException {
        final Path configPath = Paths.get(CONFIG_DIR);
        if (Files.notExists(configPath)) {
            Files.createDirectories(configPath.getParent());
            Files.createFile(configPath);
        }
    }

    private boolean setSetting(String key, String value) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        key = key.replace(SEPARATOR, "");
        final String entry = key + SEPARATOR_FULL + (value == null ? "" : value);
        try {
            ensureConfigFileExists();
            final Path configPath = new File(CONFIG_DIR).toPath();
            final List<String> lines = new ArrayList<>(Files.readAllLines(configPath));
            boolean keyExists = false;
            for (int i = 0; i < lines.size(); ++i) {
                final String line = lines.get(i);
                if (line.startsWith(key + SEPARATOR_FULL)) {
                    if (value == null || value.isEmpty()) {
                        lines.remove(i);
                    } else {
                        lines.set(i, entry);
                    }
                    keyExists = true;
                    break;
                }
            }
            if (!keyExists && value != null && !value.isEmpty()) {
                lines.add(entry);
            }
            Files.write(configPath, lines);
            return true;
        }
        catch (IOException ex) {
            return false;
        }
    }

    private String retrieveSetting(String key) {
        try {
            ensureConfigFileExists();
            final Path configPath = new File(CONFIG_DIR).toPath();
            final List<String> lines = Files.readAllLines(configPath);
            for (final String line : lines) {
                if (line.startsWith(key + SEPARATOR_FULL)) {
                    return line.substring((key + SEPARATOR_FULL).length());
                }
            }
        }
        catch (IOException ex) {}
        return null;
    }

    private String validateProfileName(String profileName, String currentName) {
        if (profileName.isEmpty()) {
            return "Profile name cannot be empty.";
        }
        if (profileName.endsWith(".") || profileName.endsWith(" ")) {
            return "Profile name cannot end with a space or period.";
        }
        for (char c : profileName.toCharArray()) {
            if (c < 32 || containsInvalidProfileChar(c)) {
                return "Profile name contains invalid characters.";
            }
        }
        for (Profile profile : profiles) {
            if (profile.getName().equalsIgnoreCase(profileName) && (currentName == null || !profile.getName().equalsIgnoreCase(currentName))) {
                return "Profile already exists: " + profileName;
            }
        }
        return null;
    }

    private boolean containsInvalidProfileChar(char c) {
        for (char invalidChar : INVALID_PROFILE_NAME_CHARS) {
            if (invalidChar == c) {
                return true;
            }
        }
        return false;
    }

    private String normalizeProfileName(String name) {
        return name == null ? "" : name.trim();
    }
}
