package mine.coding;

import net.fabricmc.api.ModInitializer;
import mine.coding.network.MineCodeNetworking;
import mine.coding.command.MineCodeCommand;
import mine.coding.server.MineCodeService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MineCode implements ModInitializer {
	public static final String MOD_ID = "minecode";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		MineCodeNetworking.registerServer();
		MineCodeCommand.register();
		ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->MineCodeService.disconnect(handler.player));
		ServerLifecycleEvents.SERVER_STOPPED.register(MineCodeService::stop);
		ServerTickEvents.END_SERVER_TICK.register(MineCodeService::tick);
		LOGGER.info("MineCode server initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
