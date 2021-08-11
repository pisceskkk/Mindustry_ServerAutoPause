package cn.pisceskkk;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
import mindustry.*;
import mindustry.game.EventType;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.net.Administration;
import static arc.util.Log.info;

public class ServerAutoPause extends Plugin{
    private boolean enabled = true;
    private int playerCount = 0;

    //called when game initializes
    @Override
    public void init(){
        Events.on(EventType.WorldLoadEvent.class, event->{
            if (enabled) {
                if (playerCount == 0 && Vars.state.serverPaused == false) {
                    String message = Strings.format("Game Paused Automatically.");
                    if(Administration.Config.showConnectMessages.bool()) info(message);
                    Vars.state.serverPaused = true;
                }
            }
        });
        Events.on(EventType.PlayerJoin.class, event -> {
            if (enabled) {
                if (playerCount == 0 && Vars.state.serverPaused == true) {
                    String message = Strings.format("Game Started Automatically.");
                    if(Administration.Config.showConnectMessages.bool()) info(message);
                    Vars.state.serverPaused = false;
                }
                playerCount += 1;
            }
        });

        Events.on(EventType.PlayerLeave.class, event -> {
            if (enabled) {
                playerCount -= 1;
                if (playerCount == 0 && Vars.state.serverPaused == false) {
                    String message = Strings.format("Game Paused Automatically.");
                    if(Administration.Config.showConnectMessages.bool()) info(message);
                    Vars.state.serverPaused = true;
                }
            }
        });
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("autopause", "<on/off>", "Enabled/disable autopause", (arg, player) -> {
            if (arg.length == 0) {
                Log.info("[scarlet]Error: Second parameter required: 'on' or 'off'");
            }

            if (!(arg[0].equals("on") || arg[0].equals("off"))) {
                Log.info("[scarlet]Error: Second parameter must be either 'on' or 'off'");
            }

            enabled = arg[0].equals("on");
        });
    }

    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("autopause", "<on/off>", "Enabled/disable autopause", (arg, player) -> {
            if (arg.length == 0) {
                player.sendMessage("[scarlet]Error: Second parameter required: 'on' or 'off'");
            }

            if (!(arg[0].equals("on") || arg[0].equals("off"))) {
                player.sendMessage("[scarlet]Error: Second parameter must be either 'on' or 'off'");
            }

            enabled = arg[0].equals("on");
        });
    }
}
