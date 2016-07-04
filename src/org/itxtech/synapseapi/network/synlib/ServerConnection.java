package org.itxtech.synapseapi.network.synlib;

import cn.nukkit.Server;
import cn.nukkit.utils.Binary;
import org.itxtech.synapseapi.utils.Util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Created by boybook on 16/6/24.
 */
public class ServerConnection {
    public static final byte[] MAGIC_BYTES = new byte[]{
            (byte) 0x35, (byte) 0xac, (byte) 0x66, (byte) 0xbf
    };

    private byte[] receiveBuffer = new byte[0];
    private byte[] sendBuffer = new byte[0];
    /**
     * @var resource
     */
    private SynapseSocket socket;
    private String ip;
    private int port;
    /**
     * @var SynapseClient
     */
    private SynapseClient server;
    private long lastCheck;
    private boolean connected;

    public ServerConnection(SynapseClient server, SynapseSocket socket) {
        this.server = server;
        this.socket = socket;
        this.ip = socket.getSocket().socket().getInetAddress().getHostAddress();
        this.port = socket.getSocket().socket().getPort();

        this.lastCheck = System.currentTimeMillis();
        this.connected = true;

        this.run();
    }

    public void run() {
        this.tickProcessor();
    }

    private void tickProcessor() {
        while (!this.server.isShutdown()) {
            long start = System.currentTimeMillis();
            try {
                this.tick();
            } catch (Exception e) {
                Server.getInstance().getLogger().logException(e);
            }

            long time = System.currentTimeMillis();
            if (time - start < 1) {  //todo TPS ???
                try {
                    Thread.sleep(1 - time + start);
                } catch (InterruptedException e) {
                    //ignore
                }
            }
        }
        try {
            this.tick();
        } catch (Exception e) {
            Server.getInstance().getLogger().logException(e);
        }
        this.socket.close();
    }

    private void tick() throws Exception {
        if(this.update()) {
            while(this.receivePacket());
            while(this.sendPacket());
        }
    }

    private boolean receivePacket() throws Exception{
        byte[] packet = this.readPacket();
        if(packet != null && packet.length > 0){
            this.server.pushThreadToMainPacket(packet);
            return true;
        }
        return false;
    }

    private boolean sendPacket() throws Exception{
        byte[] packet = this.server.readMainToThreadPacket();
        if(packet != null && packet.length > 0){
            this.writePacket(packet);
            return true;
        }
        return false;
    }

    public String getHash() {
        return this.getIp() + ":" + this.getPort();
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public SynapseSocket getSocket() {
        return socket;
    }

    public boolean update() throws Exception {
        if (this.server.needReconnect && this.connected) {
            this.connected = false;
            this.server.needReconnect = false;
        }
        if (this.connected) {
            try {
                byte[] buffer = this.socket.readPacket();
                if(buffer.length > 0){
                    this.receiveBuffer = Binary.appendBytes(buffer, this.receiveBuffer);
                }
                if (this.sendBuffer.length > 0) {
                    this.socket.getSocket().write(ByteBuffer.wrap(this.sendBuffer));
                    this.sendBuffer = new byte[0];
                }
                return true;
            } catch (IOException e) {
                int err = e.hashCode();  //todo ??????
                if (err == 10057 || err == 10054) {
                    this.server.getLogger().error("Synapse connection has disconnected unexpectedly");
                    this.connected = false;
                    this.server.setConnected(false);
                    this.server.setNeedAuth(true);
                }
                return false;
            }
        } else {
            long time;
            if (((time = System.currentTimeMillis()) - this.lastCheck) >= 3) {//re-connect
                this.server.getLogger().notice("Trying to re-connect to Synapse Server");
                if (this.socket.connect()) {
                    this.connected = true;
                    this.port = this.socket.getPort();
                    this.server.setConnected(true);
                    this.server.setNeedAuth(true);
                }
                this.lastCheck = time;
            }
            return false;
        }
    }

    public byte[] readPacket() throws Exception {
        String str = Arrays.toString(this.receiveBuffer);
        String[] arr = str.split(Arrays.toString(MAGIC_BYTES),2);
        if(arr.length <= 2){
            if(arr.length == 1){
                if (arr[0].length() >= 0) {
                    this.receiveBuffer = new byte[0];
                }else{
                    return new byte[0];
                }
            }else{
                this.receiveBuffer = arr[1].getBytes();
            }
            byte[] buffer;
            buffer = arr[0].getBytes();
            if(buffer.length < 4){
                return new byte[0];
            }
            int len = Binary.readLInt(Arrays.copyOfRange(buffer, 0, 4));
            if(len != Arrays.copyOfRange(buffer, 4, buffer.length).length){
                throw new Exception("Wrong packet buffer");
            }
            return buffer;
        }
        return new byte[0];
    }

    public void writePacket(byte[] data) {
        byte[] buffer = Util.concatByte(Binary.writeLInt(data.length), data, ServerConnection.MAGIC_BYTES);
        this.sendBuffer = Binary.appendBytes(buffer, this.sendBuffer);
    }
}
