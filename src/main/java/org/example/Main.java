package org.example;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import javax.net.ssl.SSLException;
import java.io.File;
import java.security.cert.CertificateException;
import java.util.HashSet;
import java.util.Set;

public class Main {

    public static final Set<Object> set = new HashSet<>();

    public static void main(String[] args) {


        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast("proxy", new HttpProxyClientHandler());
                        }
                    })
                    .bind(8080).sync().channel().closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static class HttpProxyClientHandler extends ChannelInboundHandlerAdapter {

        /*代理服务端channel*/
        private Channel clientChannel;
        /*目标主机channel*/
        private Channel remoteChannel;
        /*解析真实客户端的header*/
        private HttpProxyClientHeader header = new HttpProxyClientHeader();


        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            clientChannel = ctx.channel();
        }

        /**
         * 注意在真实客户端请求一个页面的时候，此方法不止调用一次，
         * 这是TCP底层决定的（拆包/粘包）
         */
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws SSLException, CertificateException, InterruptedException {
            if (header.isComplete()) {
                /*如果在真实客户端的本次请求中已经解析过header了，
                说明代理客户端已经在目标主机建立了连接，直接将真实客户端的数据写给目标主机*/
//                remoteChannel.writeAndFlush(msg); // just forward
                return;
            }

            ByteBuf in = (ByteBuf) msg;
            header.digest(in);/*解析目标主机信息*/
//            if(!header.getHost().contains("baidu")){
//                ctx.channel().close();
//                return;
//            }

            if (!header.isComplete()) {
                /*如果解析过一次header之后未完成解析，直接返回，释放buf*/
                in.release();
                return;
            }


            if (header.isHttps()) {
                CertificateUtil.createHostCertificate(header.getHost(), "root.crt", "root.key");
                ByteBuf o = Unpooled.wrappedBuffer("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());

                clientChannel.writeAndFlush(o).sync();
                SslContext build = SslContextBuilder
                        .forServer(new File(CertificateUtil.genCrtFileName(header.getHost())), new File(CertificateUtil.genKeyFileName(header.getHost())))

                        .build();

                clientChannel.pipeline().addFirst("ssl", new SslHandler(build.newEngine(ctx.channel().alloc())));
                clientChannel.pipeline().remove("proxy");

                clientChannel.pipeline().addLast(new HttpServerCodec());
                clientChannel.pipeline().addLast(new HttpObjectAggregator(1024 * 1000 * 1000));
                clientChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                        flushAndClose(clientChannel);
                    }

                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        remoteChannel.writeAndFlush(msg);
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                        flushAndClose(ctx.channel());
                        remoteChannel.close();
                    }
                });

            } else {
                //todo
            }
            /**
             *
             * 下面为真实客户端第一次来到的时候，代理客户端向目标客户端发起连接
             */
            Bootstrap b = new Bootstrap();
            NioEventLoopGroup group = new NioEventLoopGroup();
            b.group(group) // use the same EventLoop
                    .channel(clientChannel.getClass())
                    .handler(new ChannelInitializer() {

                        @Override
                        protected void initChannel(Channel channel) throws Exception {
                            SslContext build = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                            SslHandler sslHandler = new SslHandler(build.newEngine(channel.alloc()));
                            sslHandler.setHandshakeTimeoutMillis(100000);
                            channel.pipeline()
                                    .addLast(sslHandler)
                                    .addLast(new HttpClientCodec())
                                    .addLast(new HttpObjectAggregator(1024 * 1000 * 1000)).addLast(new ChannelInboundHandlerAdapter() {
                                        @Override
                                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//
                                            ChannelFuture channelFuture = clientChannel.writeAndFlush(msg);
                                            channelFuture.addListener((ChannelFutureListener) channelFuture1 -> {
                                                clientChannel.close();
                                                remoteChannel.close();
                                            });

                                        }

                                        @Override
                                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                            remoteChannel.close();
                                            flushAndClose(clientChannel);
                                        }


                                        @Override
                                        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                            remoteChannel.close();
                                            flushAndClose(clientChannel);

                                        }

                                    }).addLast();
                        }
                    });
            ChannelFuture f = b.connect(header.getHost(), header.getPort()).sync();


            Channel channel = f.channel();

             channel.closeFuture().addListener((ChannelFutureListener) channelFuture -> {
                group.shutdownGracefully();
            });
            remoteChannel = channel;


        }


        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            flushAndClose(remoteChannel);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
            e.printStackTrace();
            flushAndClose(clientChannel);
        }

        private void flushAndClose(Channel ch) {
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }
    }


    /**
     * 真实主机的请求头信息
     */
    private static class HttpProxyClientHeader {
        private String method;//请求类型
        private String host;//目标主机
        private int port;//目标主机端口
        private boolean https;//是否是https
        private boolean complete;//是否完成解析
        private ByteBuf byteBuf = Unpooled.buffer();


        private final StringBuilder lineBuf = new StringBuilder();


        public String getMethod() {
            return method;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public boolean isHttps() {
            return https;
        }

        public boolean isComplete() {
            return complete;
        }

        public ByteBuf getByteBuf() {
            return byteBuf;
        }

        /**
         * 解析header信息，建立连接
         * HTTP 请求头如下
         * GET http://www.baidu.com/ HTTP/1.1
         * Host: www.baidu.com
         * User-Agent: curl/7.69.1
         * Accept:
         *//*
         Proxy-Connection:Keep-Alive

         HTTPS请求头如下
         CONNECT www.baidu.com:443 HTTP/1.1
         Host: www.baidu.com:443
         User-Agent: curl/7.69.1
         Proxy-Connection: Keep-Alive

         * @param in
         */
        public void digest(ByteBuf in) {
            while (in.isReadable()) {
                if (complete) {
                    throw new IllegalStateException("already complete");
                }
                String line = readLine(in);
//                System.out.println(line);
                if (line == null) {
                    return;
                }
                if (method == null) {
                    method = line.split(" ")[0]; // the first word is http method name
                    https = method.equalsIgnoreCase("CONNECT"); // method CONNECT means https
                }
                if (line.startsWith("Host: ")) {
                    String[] arr = line.split(":");
                    host = arr[1].trim();
                    if (arr.length == 3) {
                        port = Integer.parseInt(arr[2]);
                    } else if (https) {
                        port = 443; // https
                    } else {
                        port = 80; // http
                    }
                }
                if (line.isEmpty()) {
                    if (host == null || port == 0) {
                        throw new IllegalStateException("cannot find header \'Host\'");
                    }
                    byteBuf = byteBuf.asReadOnly();
                    complete = true;
                    break;
                }
            }
//            System.out.println(this);
//            System.out.println("--------------------------------------------------------------------------------");
        }

        private String readLine(ByteBuf in) {
            while (in.isReadable()) {
                byte b = in.readByte();
                byteBuf.writeByte(b);
                lineBuf.append((char) b);
                int len = lineBuf.length();
                if (len >= 2 && lineBuf.substring(len - 2).equals("\r\n")) {
                    String line = lineBuf.substring(0, len - 2);
                    lineBuf.delete(0, len);
                    return line;
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return "HttpProxyClientHeader{" +
                    "method='" + method + '\'' +
                    ", host='" + host + '\'' +
                    ", port=" + port +
                    ", https=" + https +
                    ", complete=" + complete +
                    '}';
        }
    }


}

