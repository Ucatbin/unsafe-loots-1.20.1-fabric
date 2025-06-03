package unsafeloots;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unsafeloots.item.ModItemGroups;
import unsafeloots.item.ModItems;

public class UnsafeLoots implements ModInitializer {
	public static final String MOD_ID = "unsafe-loots";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModItemGroups.registerItemGroups();
		ModItems.registerModItems();
	}
}