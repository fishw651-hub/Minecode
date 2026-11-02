package mine.coding.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import mine.coding.client.screen.AiConsoleScreen;

public class MineCodeClient implements ClientModInitializer {
	private static KeyMapping openConsole;

	@Override
	public void onInitializeClient() {
		openConsole = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minecode.open_console", InputConstants.Type.KEYBOARD, InputConstants.KEY_O, KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minecode", "console"))));
		ClientNetworking.registerClient();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openConsole.consumeClick()) {
				client.setScreenAndShow(new AiConsoleScreen());
			}
		});
	}
}
