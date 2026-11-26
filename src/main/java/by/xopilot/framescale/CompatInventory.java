package by.xopilot.framescale;

import net.neoforged.fml.ModList;
import java.util.Map;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

final class CompatInventory {
    static Map<String,String> loaded() {
        var result = new TreeMap<String,String>();
        for (var mod : ModList.get().getMods()) result.put(mod.getModId(), mod.getVersion().toString());
        return result;
    }
    static String summary(Map<String,String> mods) {
        var s = new StringBuilder();
        for (String id : new String[]{"neoforge","iris","sodium","gpu_tape","gputape","distanthorizons","num_flux","super_resolution"})
            if (mods.containsKey(id)) s.append(id).append('=').append(mods.get(id)).append(' ');
        return mods.size()+" loaded IDs | "+s+"| "+CompatPolicy.evaluate(mods).reason();
    }
    static Path export(Path gameDir) throws IOException {
        var mods=loaded();
        var file=Files.createTempFile(gameDir, "framescale-compat-", ".txt");
        var text=new StringBuilder(summary(mods)).append('\n');
        mods.forEach((id,version)->text.append(id).append('\t').append(version).append('\n'));
        Files.writeString(file,text,StandardCharsets.UTF_8);
        return file.getFileName();
    }
}
