package android.network.socket;

import android.network.socket.codec.Decode;
import android.network.socket.codec.Encode;
import android.network.socket.codec.Handle;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DramSocket implements Runnable {

    private InetSocketAddress address;
    private ExecutorService pool;
    private Socket socket;
    private WriteRunnable writeRunnable;
    private Encode encode;
    private Decode decode;
    private Handle handle;
    private boolean running;

    public DramSocket() {
        pool = Executors.newFixedThreadPool(2);
    }

    public void connect(String host, int port) {
        this.address = new InetSocketAddress(host, port);
    }

    public void connect(InetSocketAddress address) {
        this.address = address;
    }

    public <E, D> void processor(Encode<E> encode, Decode<D> decode, Handle<D> handle) {
        this.encode = encode;
        this.decode = decode;
        this.handle = handle;
    }

    public void start() {
        if (encode == null) {
            throw new NullPointerException("请设置消息编码器");
        }
        if (decode == null) {
            throw new NullPointerException("请设置消息解码器");
        }
        if (handle == null) {
            throw new NullPointerException("请设置消息处理器");
        }
        if (address == null) {
            throw new NullPointerException("请设置远程连接地址");
        }
        if (running) {
            return;
        }
        running = true;
        writeRunnable = new WriteRunnable(encode);
        pool.execute(this);
    }

    @Override
    public void run() {
        synchronized (this) {
            while (running) {
                try {
                    socket = new Socket();
                    socket.connect(address);
                    if (socket.isConnected()) {
                        writeRunnable.setOutputStream(socket.getOutputStream());
                        pool.execute(writeRunnable);
                        handle.onStatus(Handle.STATUS_CONNECTED);
                        read(socket, decode, handle);//阻塞方法
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    try {
                        handle.onStatus(Handle.STATUS_FAIL);
                        socket = null;
                        this.wait(6000);
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            }
        }
    }

    public void stop() {
        running = false;
        if (writeRunnable != null) {
            writeRunnable.stop();
            writeRunnable = null;
        }
        if (socket != null) {
            shutdownInput(socket);
            shutdownOutput(socket);
            close(socket);
            socket = null;
            if (handle != null) {
                handle.onStatus(Handle.STATUS_DISCONNECT);
            }
        }

    }

    private <D> void read(Socket socket, Decode<D> decode, Handle<D> handle) throws Exception {
        final ByteBuffer buffer = ByteBuffer.allocate(102400);//解码需要操作的buffer
        byte[] bytes = new byte[buffer.capacity()];//读取的缓冲区
        byte[] cache = new byte[buffer.capacity()];//缓存没有被解码的缓冲区
        byte[] swap = new byte[buffer.capacity()];//用于和缓存缓冲区交换用的缓冲区
        int cacheLength = 0;//缓存的长度
        int readLength;//读取到的数据长度
        InputStream stream = socket.getInputStream();
        while ((readLength = stream.read(bytes)) > 0) {
            System.arraycopy(bytes, 0, cache, cacheLength, readLength);//把读取到的数据拷贝到上次缓存缓冲区的后面
            cacheLength += readLength;//缓存长度=上次的缓存长度+读取的数据长度
            buffer.clear();//清除重置解码的ByteBuffer
            buffer.put(cache, 0, cacheLength);
            buffer.flip();//切换到读模式
            buffer.mark();//先标记当前开始读取的点，用于后面不够解码后reset操作
            D data;
            while (buffer.hasRemaining() && ((data = decode.decode(buffer)) != null)) {//判断如果ByteBuffer后面有可读数据并且解码一次
                handle.onReceive(data);//把解码的数据回调给Handler
                if (buffer.hasRemaining()) {//再次判断ByteBuffer后面是否还有可读数据
                    int remaining = buffer.remaining();//ByteBuffer剩余没有读取的数据长度
                    int position = buffer.position();//ByteBuffer当前读取的位置
                    System.arraycopy(cache, position, swap, 0, remaining);//拷贝缓存剩余长度的数据到交换缓冲区
                    System.arraycopy(swap, 0, cache, 0, remaining);//在把交换缓冲区的数据拷贝的缓存缓冲区用于下次解码
                    cacheLength = remaining;//重置缓存缓冲区长度为剩余数据长度
                    buffer.clear();//再次清除重置解码的ByteBuffer
                    buffer.put(cache, 0, cacheLength);
                    buffer.flip();//切换到读模式
                }
                buffer.mark();//再次标记当前开始读取点
            }
            buffer.reset();//上面解码完成后重置到make读取点
            if (buffer.hasRemaining()) {//判断是否还有数据可读
                int remaining = buffer.remaining();//剩余可读长度
                buffer.get(cache, 0, remaining);//将剩余数据拷贝到缓存缓冲区
                cacheLength = remaining;//缓存数据长度为当前剩余数据长度
            } else {
                cacheLength = 0;//如果没有可读的数据 缓存数据长度为0
            }
        }
    }

    private static final class WriteRunnable<E> implements Runnable {
        private Vector<E> vector = new Vector<>();
        private Encode<E> encode;
        private OutputStream stream;
        private boolean sending;

        private WriteRunnable(Encode<E> encode) {
            this.encode = encode;
        }

        private void setOutputStream(OutputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            synchronized (this) {
                sending = true;
                while (sending) {
                    if (vector.size() == 0) {
                        try {
                            this.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    while (vector.size() > 0) {
                        E data = vector.remove(0);
                        encode.encode(data, null);
                        if (stream != null) {
                            try {
                                stream.write(1);
                                stream.flush();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }

        private void stop() {
            sending = false;
            this.stream = null;
            synchronized (this) {
                this.notify();
            }
        }

        private void send(E data) {
            this.vector.add(data);
            synchronized (this) {
                this.notify();
            }
        }
    }

    private static void shutdownInput(Socket socket) {
        if (socket != null && !socket.isInputShutdown()) {
            try {
                socket.shutdownInput();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void shutdownOutput(Socket socket) {
        if (socket != null && !socket.isOutputShutdown()) {
            try {
                socket.shutdownOutput();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void close(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}