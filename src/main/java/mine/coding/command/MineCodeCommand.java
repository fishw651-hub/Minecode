package mine.coding.command;

import mine.coding.server.MineCodeService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;

public final class MineCodeCommand {
    private MineCodeCommand(){}
    public static void register(){CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)->
        dispatcher.register(Commands.literal("minecode")
            .executes(context->{MineCodeService.open(context.getSource().getPlayerOrException());return 1;})
            .then(Commands.literal("status").executes(context->{ServerPlayer player=context.getSource().getPlayerOrException();MineCodeService.handle(player,"{\"op\":\"context\"}");return 1;}))
            .then(Commands.literal("logs").requires(MineCodeService::hasWorldPermission).executes(context->{MineCodeService.handle(context.getSource().getPlayerOrException(),"{\"op\":\"logs\"}");return 1;}))
            .then(Commands.literal("clear-memory").requires(MineCodeService::hasWorldPermission).executes(context->{MineCodeService.handle(context.getSource().getPlayerOrException(),"{\"op\":\"clearMemory\"}");return 1;}))));}
}
