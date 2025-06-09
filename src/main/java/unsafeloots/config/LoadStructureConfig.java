package unsafeloots.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LoadStructureConfig {
    public List<String> whitelist = new ArrayList<>();
    public List<String> blacklist = new ArrayList<>();

    public static LoadStructureConfig load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("unsafeloots/structures.json");
        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                Files.writeString(configPath, getDefaultConfig());
            }

            String json = Files.readString(configPath);
            return new Gson().fromJson(json, LoadStructureConfig.class);
        } catch (IOException e) {
            return new LoadStructureConfig();
        }
    }

    private static String getDefaultConfig() {
        JsonObject json = new JsonObject();
        json.add("whitelist", new Gson().toJsonTree(List.of("minecraft:village_plains")));
        json.add("blacklist", new Gson().toJsonTree(List.of("minecraft:stronghold")));
        return new Gson().toJson(json);
    }
}