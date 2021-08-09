package cn.pisceskkk;

import arc.Events;
import arc.graphics.*;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.*;
import arc.util.serialization.Base64Coder;
import mindustry.*;
import mindustry.core.GameState.State;
import mindustry.core.Version;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.net.Administration;
import mindustry.net.Packets;
import mindustry.game.EventType.*;
import mindustry.net.Administration.*;

import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static arc.util.Log.err;
import static arc.util.Log.info;
import static mindustry.Vars.*;
import static mindustry.Vars.platform;

public class ServerAutoPause extends Plugin{
    private ReusableByteOutStream writeBuffer = new ReusableByteOutStream(127);
    private Writes outputBuffer = new Writes(new DataOutputStream(writeBuffer));
    //called when game initializes
    @Override
    public void init(){
        net.handleServer(Packets.Connect.class, (con, connect) -> {
            Events.fire(new ConnectionEvent(con));

            if(Vars.netServer.admins.isIPBanned(connect.addressTCP) || Vars.netServer.admins.isSubnetBanned(connect.addressTCP)){
                con.kick(Packets.KickReason.banned);
                return;
            }
            /*
              check if there is no player and game is paused, then start the game.
             */
            if(Groups.player.size() == 0 && Vars.state.serverPaused == true) {
                String message = Strings.format("Game Started.");
                if(Administration.Config.showConnectMessages.bool()) info(message);
                Vars.state.serverPaused = false;
            }
        });


        Vars.net.handleServer(Packets.Disconnect.class, (con, packet) -> {
            if(con.player != null){
                Vars.netServer.onDisconnect(con.player, packet.reason);
                /*
                  check if there is no player, then pause the game.
                 */
                if(Groups.player.size() == 0) {
                    Vars.state.serverPaused = true;
                    String message = Strings.format("Game Paused.");
                    if(Administration.Config.showConnectMessages.bool()) info(message);
                }
            }
        });


        net.handleServer(Packets.ConnectPacket.class, (con, packet) -> {
            if(con.kicked) return;

            if(con.address.startsWith("steam:")){
                packet.uuid = con.address.substring("steam:".length());
            }

            con.connectTime = Time.millis();

            String uuid = packet.uuid;
            byte[] buuid = Base64Coder.decode(uuid);
            CRC32 crc = new CRC32();
            crc.update(buuid, 0, 8);
            ByteBuffer buff = ByteBuffer.allocate(8);
            buff.put(buuid, 8, 8);
            buff.position(0);
            if(crc.getValue() != buff.getLong()){
                con.kick(Packets.KickReason.clientOutdated);
                return;
            }

            if(Vars.netServer.admins.isIPBanned(con.address) || Vars.netServer.admins.isSubnetBanned(con.address)) return;

            if(con.hasBegunConnecting){
                con.kick(Packets.KickReason.idInUse);
                return;
            }

            PlayerInfo info = Vars.netServer.admins.getInfo(uuid);

            con.hasBegunConnecting = true;
            con.mobile = packet.mobile;

            if(packet.uuid == null || packet.usid == null){
                con.kick(Packets.KickReason.idInUse);
                return;
            }

            if(Vars.netServer.admins.isIDBanned(uuid)){
                con.kick(Packets.KickReason.banned);
                return;
            }

            if(Time.millis() < Vars.netServer.admins.getKickTime(uuid, con.address)){
                con.kick(Packets.KickReason.recentKick);
                return;
            }

            if(Vars.netServer.admins.getPlayerLimit() > 0 && Groups.player.size() >= Vars.netServer.admins.getPlayerLimit() && !netServer.admins.isAdmin(uuid, packet.usid)){
                con.kick(Packets.KickReason.playerLimit);
                return;
            }

            Seq<String> extraMods = packet.mods.copy();
            Seq<String> missingMods = mods.getIncompatibility(extraMods);

            if(!extraMods.isEmpty() || !missingMods.isEmpty()){
                //can't easily be localized since kick reasons can't have formatted text with them
                StringBuilder result = new StringBuilder("[accent]Incompatible mods![]\n\n");
                if(!missingMods.isEmpty()){
                    result.append("Missing:[lightgray]\n").append("> ").append(missingMods.toString("\n> "));
                    result.append("[]\n");
                }

                if(!extraMods.isEmpty()){
                    result.append("Unnecessary mods:[lightgray]\n").append("> ").append(extraMods.toString("\n> "));
                }
                con.kick(result.toString(), 0);
            }

            if(!Vars.netServer.admins.isWhitelisted(packet.uuid, packet.usid)){
                info.adminUsid = packet.usid;
                info.lastName = packet.name;
                info.id = packet.uuid;
                Vars.netServer.admins.save();
                Call.infoMessage(con, "You are not whitelisted here.");
                info("&lcDo &lywhitelist-add @&lc to whitelist the player &lb'@'", packet.uuid, packet.name);
                con.kick(Packets.KickReason.whitelist);
                return;
            }

            if(packet.versionType == null || ((packet.version == -1 || !packet.versionType.equals(Version.type)) && Version.build != -1 && !Vars.netServer.admins.allowsCustomClients())){
                con.kick(!Version.type.equals(packet.versionType) ? Packets.KickReason.typeMismatch : Packets.KickReason.customClient);
                return;
            }

            boolean preventDuplicates = headless && netServer.admins.isStrict();

            if(preventDuplicates){
                if(Groups.player.contains(p -> p.name.trim().equalsIgnoreCase(packet.name.trim()))){
                    con.kick(Packets.KickReason.nameInUse);
                    return;
                }

                if(Groups.player.contains(player -> player.uuid().equals(packet.uuid) || player.usid().equals(packet.usid))){
                    con.kick(Packets.KickReason.idInUse);
                    return;
                }
            }

            packet.name = fixName(packet.name);

            if(packet.name.trim().length() <= 0){
                con.kick(Packets.KickReason.nameEmpty);
                return;
            }

            if(packet.locale == null){
                packet.locale = "en";
            }

            String ip = con.address;

            Vars.netServer.admins.updatePlayerJoined(uuid, ip, packet.name);

            if(packet.version != Version.build && Version.build != -1 && packet.version != -1){
                con.kick(packet.version > Version.build ? Packets.KickReason.serverOutdated : Packets.KickReason.clientOutdated);
                return;
            }

            if(packet.version == -1){
                con.modclient = true;
            }

            Player player = Player.create();
            player.admin = Vars.netServer.admins.isAdmin(uuid, packet.usid);
            player.con = con;
            player.con.usid = packet.usid;
            player.con.uuid = uuid;
            player.con.mobile = packet.mobile;
            player.name = packet.name;
            player.locale = packet.locale;
            player.color.set(packet.color).a(1f);

            //save admin ID but don't overwrite it
            if(!player.admin && !info.admin){
                info.adminUsid = packet.usid;
            }

            try{
                writeBuffer.reset();
                player.write(outputBuffer);
            }catch(Throwable t){
                con.kick(Packets.KickReason.nameEmpty);
                err(t);
                return;
            }

            con.player = player;

            //playing in pvp mode automatically assigns players to teams
            player.team(Vars.netServer.assignTeam(player));

            Vars.netServer.sendWorldData(player);

            platform.updateRPC();

            Events.fire(new PlayerConnect(player));

            /*
              check if there is no player and game is paused, then start the game.
             */
            if(Groups.player.size() == 0 && Vars.state.serverPaused == true) {
                String message = Strings.format("Game Started.");
                if(Administration.Config.showConnectMessages.bool()) info(message);
                Vars.state.serverPaused = false;
            }

        });

    }

    @Override
    public void registerServerCommands(CommandHandler handler) {

    }

    String fixName(String name){
        name = name.trim();
        if(name.equals("[") || name.equals("]")){
            return "";
        }

        for(int i = 0; i < name.length(); i++){
            if(name.charAt(i) == '[' && i != name.length() - 1 && name.charAt(i + 1) != '[' && (i == 0 || name.charAt(i - 1) != '[')){
                String prev = name.substring(0, i);
                String next = name.substring(i);
                String result = checkColor(next);

                name = prev + result;
            }
        }

        StringBuilder result = new StringBuilder();
        int curChar = 0;
        while(curChar < name.length() && result.toString().getBytes(Strings.utf8).length < maxNameLength){
            result.append(name.charAt(curChar++));
        }
        return result.toString();
    }

    String checkColor(String str){
        for(int i = 1; i < str.length(); i++){
            if(str.charAt(i) == ']'){
                String color = str.substring(1, i);

                if(Colors.get(color.toUpperCase()) != null || Colors.get(color.toLowerCase()) != null){
                    Color result = (Colors.get(color.toLowerCase()) == null ? Colors.get(color.toUpperCase()) : Colors.get(color.toLowerCase()));
                    if(result.a <= 0.8f){
                        return str.substring(i + 1);
                    }
                }else{
                    try{
                        Color result = Color.valueOf(color);
                        if(result.a <= 0.8f){
                            return str.substring(i + 1);
                        }
                    }catch(Exception e){
                        return str;
                    }
                }
            }
        }
        return str;
    }

}
