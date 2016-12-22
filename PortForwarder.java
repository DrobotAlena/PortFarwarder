import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;

public class PortForwarder {

    private static int iPort;
    private static int rPort;
    private static String rHost;
    private static int bufferSize = 10000;


    static class Attachment {

        ByteBuffer in;
        ByteBuffer out;
        SelectionKey pairKey;
        boolean inIsRead;

        public Attachment(SelectionKey pairKey) {
            this.in = ByteBuffer.allocate(bufferSize);
            this.out = ByteBuffer.allocate(bufferSize);
            this.pairKey = pairKey;
            this.inIsRead = true;
        }
    }


    public static void main(String[] args) throws IOException {  //0 - наш порт, 1 - recv порт , 2 - recv имя
        if (args.length < 3) {
            System.out.println("ERROR: not enough arguments");
            System.exit(1);
        }
        iPort = Integer.parseInt(args[0]);
        rPort = Integer.parseInt(args[1]);
        rHost = args[2];

        Selector selector = Selector.open();
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        ssc.socket().bind(new InetSocketAddress(iPort));
        ssc.register(selector, SelectionKey.OP_ACCEPT); //SelectionKey.OP_ACCEPT - хотим только входящие соединения

        while (true) {
            int count = selector.select();
//            System.out.println("select " + count);

            if (count < 0)
                continue;
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator(); //ключи, где что-то произошло
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                if (key.isValid()) {
                    try {
                        if (key.isAcceptable()) { //принимаем соединение
                            System.out.println("have new accept " + key);
                            accept(key);
                        } else if (key.isReadable()) { //данные запись с
                            //System.out.println("Have data to read "+key);
                            read(key);
                        } else if (key.isWritable()) { //готов к записи
                            //System.out.println("Have data to write "+key);
                            write(key);
                        } else if (key.isConnectable()) {
                            connect(key);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                iterator.remove();
            }
        }
    }

    private static void accept(SelectionKey key) throws IOException { //создаем канал для клиента и его в селектор
        SocketChannel newClientChannel = ((ServerSocketChannel) key.channel()).accept();  //подключаем клиентов
        newClientChannel.configureBlocking(false);
        SelectionKey clientKey = newClientChannel.register(key.selector(), 0); //будем слушать, когда передает и читает данные

        //работа с сервером
        SocketChannel newServerChannel = SocketChannel.open();
        newServerChannel.configureBlocking(false);
        newServerChannel.connect(new InetSocketAddress(InetAddress.getByName(rHost), rPort));
        SelectionKey serverKey = newServerChannel.register(key.selector(), SelectionKey.OP_CONNECT);

        Attachment serverAttachment = new Attachment(clientKey);
        serverKey.attach(serverAttachment);
        Attachment clientAttachment = new Attachment(serverKey);
        clientKey.attach(clientAttachment);

        System.out.println("clientKey = " + clientKey);
        System.out.println("serverKey = " + serverKey);
    }

    private static void connect(SelectionKey key) throws IOException {
        SocketChannel channel = ((SocketChannel) key.channel());
        Attachment attachment = ((Attachment) key.attachment());

        channel.finishConnect();
        attachment.out = ((Attachment) attachment.pairKey.attachment()).in;
        ((Attachment) attachment.pairKey.attachment()).out = attachment.in;
        attachment.pairKey.interestOps(SelectionKey.OP_READ);
        key.interestOps(SelectionKey.OP_READ);
    }

    private static void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Attachment attachment = ((Attachment) key.attachment());
        int count = channel.read(attachment.in);
        if (count > 1) {
            System.out.println("was read " + count + " from " + key);
            attachment.inIsRead = false;
            attachment.pairKey.interestOps(attachment.pairKey.interestOps() | SelectionKey.OP_WRITE); //говорим, чтобы второй конец принимал данные
            key.interestOps(key.interestOps() ^ SelectionKey.OP_READ); //убираем интерес на передачу данных у сработавшего соединения
            attachment.in.flip(); //готовим буффер для записи
        }
        else{
            close(key);
        }
    }

    private static void write(SelectionKey key) throws IOException {
        SocketChannel channel = ((SocketChannel) key.channel());
        Attachment attachment = ((Attachment) key.attachment());

        int count = channel.write(attachment.out);
        if (count > 0) { //успешно прочитали данные
            attachment.out.compact();
            System.out.println("was write " + count + " to " + key);
            ((Attachment) attachment.pairKey.attachment()).inIsRead = true;
            attachment.pairKey.interestOps(attachment.pairKey.interestOps() | SelectionKey.OP_READ);// Добавялем ко второму концу интерес на чтение
            if (attachment.out.hasRemaining()) {
                key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE); // у своего убираем интерес на запись
            }
        }
        else{
            close(key);
        }
    }

    private static void close(SelectionKey key) throws IOException{
        key.channel().close();
        key.cancel();
        SelectionKey pairKey = ((Attachment) key.attachment()).pairKey;

        pairKey.channel().close();
        pairKey.cancel();
    }
}



