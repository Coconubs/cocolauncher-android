package net.kdt.pojavlaunch.coco;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Minecraft Server List Ping (the unauthenticated status request the multiplayer screen uses). */
public final class ServerPing {
    private ServerPing() {}

    public static final class Result {
        public final int online;
        public final int max;
        Result(int online, int max) { this.online = online; this.max = max; }
    }

    /** @return the player count, or null when the server can't be reached. Blocking. */
    public static Result ping(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);
            writeVarInt(handshake, 767); // 1.21.1; status works with any number
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, hostBytes.length);
            handshake.write(hostBytes);
            handshake.write((port >> 8) & 0xff);
            handshake.write(port & 0xff);
            writeVarInt(handshake, 1); // next state: status
            writePacket(out, handshake.toByteArray());
            writePacket(out, new byte[]{0x00}); // status request

            DataInputStream in = new DataInputStream(socket.getInputStream());
            readVarInt(in); // packet length
            if (readVarInt(in) != 0x00) return null;
            byte[] json = new byte[readVarInt(in)];
            in.readFully(json);
            JSONObject players = new JSONObject(new String(json, StandardCharsets.UTF_8)).getJSONObject("players");
            return new Result(players.optInt("online"), players.optInt("max"));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writePacket(OutputStream out, byte[] body) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, body.length);
        packet.write(body);
        out.write(packet.toByteArray());
        out.flush();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        do {
            int part = value & 0x7f;
            value >>>= 7;
            if (value != 0) part |= 0x80;
            out.write(part);
        } while (value != 0);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too long");
    }
}
