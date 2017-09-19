package connect;

import android.content.Context;
import android.os.Handler;
import android.os.Message;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import appData.GlobalVar;
import fileSlices.EncodeFile;
import fileSlices.PieceFile;
import msg.MsgValue;
import utils.IntAndBytes;
import utils.LocalInfor;
import utils.MyFileUtils;

/**
 * 用来管理SocketServer多用户连接
 * TCPServer = ServerThread + ClientThread
 * Created by Administrator on 2017/7/24 0024.
 */

public class TCPServer {
    private List<Socket> socketList = new ArrayList<Socket>();

    private ExecutorService mExecutorService = null;   //线程池
    //用来控制ServerSocket等待线程的关闭
    private volatile boolean flag_ServerThread = false; //线程标志位
    //创建一个SocketServer服务线程
    private ServerThread serverThread = new ServerThread();
    //主活动的handler
    private Handler handler = null;
    //主活动的Context
    private Context context;
    //本地的编码数据
    private EncodeFile LocalEncodeFile = null;
    //管理AP热点,记着退出时，要关闭ap，关闭监听端口
    private APHelper apHelper = null;

    public TCPServer(Handler handler, Context context) {
        this.handler = handler;
        this.context = context;
    }


    //开启ServerSocket服务
    public void StartServer(EncodeFile encodeFile) {
        LocalEncodeFile = encodeFile;
        //这里要做打开AP
        //打开热点
        apHelper = new APHelper(context);
        if (apHelper.setWifiApEnabled(APHelper.createWifiCfg(), true)) {
            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, "AP打开成功");
        } else {
            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, "AP打开失败");
        }
        if (!flag_ServerThread) {
            serverThread.start();
            System.out.println(serverThread.getName() + " 已启动");
        } else {
            //成功
            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                    "SocketServer开启成功，等待连接"
            );
        }
    }

    //SocketServer服务线程 等待client连接
    class ServerThread extends Thread {
        ServerSocket serverSocket = null;

        public ServerThread() {
            this.setName("SocketServer等待连接线程");
        }

        @Override
        public void run() {
            //创建一个SocketServer服务
            try {
                serverSocket = new ServerSocket(Constant.TCP_ServerPORT);
                serverSocket.setReuseAddress(true);   //设置上一个关闭的超时状态下可连接
            } catch (IOException e) {
                //失败
                SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                        "绑定端口" + Constant.TCP_ServerPORT + "失败"
                );
                e.printStackTrace();
            }
            //成功
            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                    "SocketServer开启成功，等待连接"
            );
            flag_ServerThread = true;
            //创建一个线程池，用来处理client
            mExecutorService = Executors.newCachedThreadPool();
            Socket client = null;
            //等待client连接
            while (flag_ServerThread) {
                try {
                    //阻塞等待连接
                    client = serverSocket.accept();
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
                String client_ip = client.getInetAddress().toString();
                for (int i = 0; i < socketList.size(); ++i) {
                    Socket s = socketList.get(i);
                    if (s.getInetAddress().toString().equals(client_ip)) {
                        socketList.remove(i);
                        try {
                            s.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    }
                }
                socketList.add(client);
                //启动一个线程处理与client的对话
                try {
                    mExecutorService.execute(new ClientThread(client)); //启动一个新的线程来处理连接
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    //处理与client的socket对话
    class ClientThread extends Thread {
        private Socket socket;
        private String client_ip;
        private String partner_phoneName = "";

        private DataInputStream in = null;   //接收
        private DataOutputStream out = null; //发送
        //标志接收循环是否该退出了
        private boolean kk = true;
        private boolean sendFinish = false;

        public ClientThread(Socket socket) {
            this.socket = socket;
            client_ip = socket.getInetAddress().toString();
            this.setName(client_ip + " 服务线程");
            System.out.println(this.getName() + "已启动");
            initialize();
        }

        private void initialize() {
            try {
                socket.setTcpNoDelay(true); //设置直接发送
                in = new DataInputStream(socket.getInputStream());     //接收
                out = new DataOutputStream(socket.getOutputStream());//发送
            } catch (IOException e) {
                e.printStackTrace();
            }

            //在此发送本机的名称和配置文件
            try {
                //在此发送本机型号
                String phoneName = LocalInfor.getPhoneModel();
                byte[] send_phoneName = phoneName.getBytes();
                int len = send_phoneName.length;
                out.write(IntAndBytes.send_instruction_len(Constant.PHONE_NAME, len));   //发送指令长度
                out.write(send_phoneName);

                //发送配置文件xml_file
                String xml_file_path = LocalEncodeFile.getStoragePath() + File.separator + "xml.txt";
                byte[] bt_xml_file = MyFileUtils.readFile(xml_file_path);
                int file_len = bt_xml_file.length;
                out.write(IntAndBytes.send_instruction_len(Constant.XML_FILE, file_len));
                out.write(bt_xml_file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            byte[] getBytes = new byte[5];
            int pieceNo = 0;
            String pieceFileName = "";
            while (socket.isConnected() && kk) {
//                if(socket.isInputShutdown()){
//                    break;
//                }
                int len = -1;
                //5个字节作为一个指令的长度
                try {
                    len = in.read(getBytes, 0, 5);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (len < 1) {
                    //结束依次循环执行下一次
                    continue;
                }
                try {
                    if (len < 5) {
                        while (true) {
                            int limit = 5 - len;
                            int num = in.read(getBytes, len, limit);
                            len = len + num;
                            if (len == 5) {
                                break;
                            }
                        }
                    }
                    byte instruction = getBytes[0];
                    //获取需读取的长度
                    byte[] bt_len = new byte[4];
                    for (int i = 0; i < 4; ++i) {
                        bt_len[i] = getBytes[i + 1];
                    }
                    int readLen = IntAndBytes.byte2int(bt_len);
                    switch (instruction) {
                        case Constant.PHONE_NAME:
                            byte[] bt_pName = (byte[]) limitReadBytes(readLen, "");
                            partner_phoneName = new String(bt_pName);
                            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                                    partner_phoneName + " 已连接"
                            );
                            break;
                        case Constant.XML_FILE:
                            String storagePath = GlobalVar.getTempPath() + File.separator + "xml.txt";
                            final File xml_file = (File) limitReadBytes(readLen, storagePath);
                            //在此处解析xml文件，得到对方的文件信息
                            //启动发送线程，向对方发送数据
                            Thread sendFileThread = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    sendToPartner(xml_file);
                                }
                            });
                            sendFileThread.setName("向 " + partner_phoneName + "发送文件线程");
                            sendFileThread.start();
                            System.out.println(sendFileThread.getName() + "已启动");
                            break;
                        case Constant.PIECE_FILE_NAME:
                            byte[] bt_pfName = (byte[]) limitReadBytes(readLen, "");
                            pieceFileName = new String(bt_pfName);
                            String s_no = pieceFileName.substring(0, pieceFileName.indexOf("."));
                            try {
                                pieceNo = Integer.parseInt(s_no);
                            } catch (NumberFormatException e) {
                                e.printStackTrace();
                                //此处解析出错，说明整个传输都有问题
                                break;
                            }
                            break;
                        case Constant.PIECE_FILE:
                            //创建接收目录
                            String path = MyFileUtils.creatFolder(LocalEncodeFile.getStoragePath(), pieceNo + "");
                            String encodeFilePath = MyFileUtils.creatFolder(path, "encodeFiles");
                            String storagePath1 = encodeFilePath + File.separator + pieceFileName;
                            File pEncodeFile = (File) limitReadBytes(readLen, storagePath1);
                            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                                    pieceFileName + " 接收完成"
                            );
                            //对LocalEncodeFile中做相应的变量处理
                            //为了适应软件运行的3种模式，此处应作方法处理
                            solveRevFile(pEncodeFile, pieceNo);
                            SendMessage(MsgValue.SET_CUR_TOTAL_TV,
                                    LocalEncodeFile.getCurrentSmallPiece(),
                                    LocalEncodeFile.getTotalSmallPiece(),
                                    null
                            );
                            //尝试解码
                            if (LocalEncodeFile.getCurrentSmallPiece() != LocalEncodeFile.getTotalSmallPiece()) {
                                break;
                            }

                            Thread decodeThread = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    if (LocalEncodeFile.recoveryFile()) {
                                        SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                                                LocalEncodeFile.getFileName() + " 解码完成");
                                    }
                                }
                            });
                            decodeThread.setName("本地尝试解码线程");
                            decodeThread.start();
                            System.out.println(decodeThread.getName() + " 已启动");
                            break;
                        //对方已经发送完毕
                        case Constant.SEND_FINISH:
                            byte[] bt_sendFinish = (byte[]) limitReadBytes(readLen, "");
                            String str_sendFinish = new String(bt_sendFinish);
                            if (str_sendFinish.equals(Constant.SEND_FINISH_STRING)) {
                                //对方已经发送完毕，如果自己也发送完，就可以关闭socket了
                                //这里不做处理，关闭的执行是，client端先执行的
                            }
                            break;
                        //socket的另一方已经关闭socket
                        case Constant.END_FLAG:
                            byte[] bt_endFlag = (byte[]) limitReadBytes(readLen, "");
                            String endFlag = new String(bt_endFlag);
                            if (endFlag.equals(Constant.END_STRING)) {
                                //对方已经退出，己方关闭socket
                                closeSocket();
                                LocalEncodeFile.solveFileChange();
                            }
                            break;
                        default:
                            //若执行到此处，则说明传输出错了
                            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                                    "Client 端接收数据出错"
                            );
                            break;
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                    //此处出错，将会影响整个传输过程
                    break;
                }

            }
        }

        //处理一个client的退出  关闭此socket
        public void closeSocket() {
            try {
                kk = false;
                out.close();
                in.close();
                socket.close();
                //关掉圆形进度球
                SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                        partner_phoneName + " 已断开连接");
            } catch (IOException e) {
                e.printStackTrace();
            }
            socketList.remove(socket);
            for (int i = 0; i < socketList.size(); ++i) {
                Socket s = socketList.get(i);
                if (s.getInetAddress().equals(client_ip)) {
                    socketList.remove(s);
                }
            }
        }

        /**
         * 按字节读取的一个方法
         * 从socket流中读取一定字节
         *
         * @param readLen
         * @param storagePath 包括文件名
         * @return
         */
        public Object limitReadBytes(int readLen, String storagePath) throws IOException {
            //读入byte数组
            if (storagePath.equals("")) {
                byte[] getBytes = new byte[readLen];
                int limitLen = readLen;
                if (readLen > readLen) {
                    limitLen = readLen;
                }
                int bytes = 0;
                while (true) {
                    int len = in.read(getBytes, bytes, limitLen);
                    bytes += len;   //记录已经写入的文件个数
                    //设置接收进度
                    limitLen = readLen - bytes;
                    if (limitLen > Constant.BUFFER_SIZE) {
                        limitLen = Constant.BUFFER_SIZE;
                    } else if (limitLen <= 0) {
                        break;
                    }
                }
                return getBytes;
            } else {
                //读入文件
                byte[] getBytes = new byte[Constant.BUFFER_SIZE];
                int limitLen = readLen;
                if (readLen > Constant.BUFFER_SIZE) {
                    limitLen = Constant.BUFFER_SIZE;
                }
                File file = new File(storagePath);
                if (!file.exists()) {
                    file.createNewFile();
                }
                FileOutputStream fos = new FileOutputStream(file);
                int bytes = 0;
                while (true) {
                    int len = in.read(getBytes, 0, limitLen);
                    fos.write(getBytes, 0, len);
                    bytes += len;   //记录已经写入的文件个数
                    //设置接收进度
                    limitLen = readLen - bytes;
                    if (limitLen > Constant.BUFFER_SIZE) {
                        limitLen = Constant.BUFFER_SIZE;
                    } else if (limitLen <= 0) {
                        break;
                    }
                }
                fos.close();
                return file;
            }

        }

        /**
         * 处理接收到的文件
         *
         * @param nc_file
         * @param pieceNo
         */
        public void solveRevFile(File nc_file, int pieceNo) {
            PieceFile my_pieceFile = null;
            for (PieceFile pieceFile : LocalEncodeFile.getPieceFileList()) {
                if (pieceFile.getPieceNo() == pieceNo) {
                    my_pieceFile = pieceFile;
                    break;
                }
            }
            int rightFileLen = 0;
            if (pieceNo == LocalEncodeFile.getTotalParts()) {
                rightFileLen = LocalEncodeFile.getRightFileLen2();
            } else {
                rightFileLen = LocalEncodeFile.getRightFileLen1();
            }
            if (my_pieceFile == null) {
                my_pieceFile = new PieceFile(LocalEncodeFile.getStoragePath(), pieceNo, LocalEncodeFile.getnK(), rightFileLen);
                LocalEncodeFile.setCurrentParts(LocalEncodeFile.getCurrentParts() + 1);
            } else {
                PieceFile pieceFile = my_pieceFile;
                //删除之前的部分文件信息
                LocalEncodeFile.getPieceFileList().remove(pieceFile);
            }
            try {
                //改写文件数目
                my_pieceFile.updateCoeffMat(nc_file);
                //标志需要重新编码
                my_pieceFile.setFileChanged(true);
                LocalEncodeFile.setCurrentSmallPiece(LocalEncodeFile.getCurrentSmallPiece() + 1);
            } catch (Exception e) {
                //接收文件出错
                e.printStackTrace();
            }finally {
                LocalEncodeFile.getPieceFileList().add(my_pieceFile);
                LocalEncodeFile.object2xml();
            }

        }

        //根据配置文件，发送给对方数据
        public void sendToPartner(File xml_file) {
            EncodeFile itsEncodeFile = EncodeFile.xml2object(xml_file, false);
            if (itsEncodeFile == null) {
                SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, "TCPServer解析对方xml配置信息出错");
                return;
            }
            int currentSmallPiece = itsEncodeFile.getCurrentSmallPiece();
            int totalSmallPiece = itsEncodeFile.getTotalSmallPiece();
            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                    partner_phoneName + " 已有/共需文件个数：" + currentSmallPiece + "/" + totalSmallPiece
            );
            if (currentSmallPiece == totalSmallPiece) {
                //说明对方已经拥有所有的数据，不用再发送
                sendFinish = true;
                //告诉对方数据已经发送完毕
                //告诉对方数据已经发送完毕
                byte[] bt_sendFinish = Constant.SEND_FINISH_STRING.getBytes();
                int len = bt_sendFinish.length;
                try {
                    out.write(IntAndBytes.send_instruction_len(Constant.SEND_FINISH, len));
                    out.write(bt_sendFinish);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return;
            }
            //本地数据
            List<PieceFile> myPieceFileList = LocalEncodeFile.getPieceFileList();
            //对方数据
            List<PieceFile> itsPieceFileList = itsEncodeFile.getPieceFileList();
            //在本地数据中查找对对方有用的数据
            List<File> usefulFiles = new ArrayList<File>();
            int nK = itsEncodeFile.getnK();
            boolean itHaveThisPart = false;  //用来标志对方是否拥有此文件部分
            for (PieceFile my_pieceFile : myPieceFileList) {
                int pieceNo = my_pieceFile.getPieceNo();
                for (PieceFile its_pieceFile : itsPieceFileList) {
                    if (its_pieceFile.getPieceNo() == pieceNo) {
                        itHaveThisPart = true;
                        int currentFileNum = its_pieceFile.getCurrentFileNum();
                        if (currentFileNum == nK) {
                            //说明对方本文件部分数据已全
                        } else {
                            //在本地找数据
                            int[][] itsCoeffMatrix = its_pieceFile.getCoeffMatrix();
                            File file = my_pieceFile.getUsefulFile(itsCoeffMatrix, currentFileNum);
                            if (file != null) {
                                usefulFiles.add(file);
                                //标志需要重新编码
                                my_pieceFile.setFileChanged(true);
                            }
                        }
                        break;
                    }
                }
                //如果对方没有此部分文件信息
                if (!itHaveThisPart) {
                    File file = my_pieceFile.getUsefulFile();
                    if (file != null) {
                        usefulFiles.add(file);
                        //标志需要重新编码
                        my_pieceFile.setFileChanged(true);
                    }
                }
            }
            //发送
            sendFile(usefulFiles);
            sendFinish = true;
            //告诉对方数据已经发送完毕
            //告诉对方数据已经发送完毕
            byte[] bt_sendFinish = Constant.SEND_FINISH_STRING.getBytes();
            int len = bt_sendFinish.length;
            try {
                out.write(IntAndBytes.send_instruction_len(Constant.SEND_FINISH, len));
                out.write(bt_sendFinish);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //发送文件
        public void sendFile(List<File> files) {
            //准备向对方发送数据
            int fileNum = files.size();
            if (fileNum == 0) {
                return;
            }
            //开始发送
            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                    "正在向 " + partner_phoneName + " 发送" + fileNum + "个文件数据"
            );
            for (int i = 0; i < fileNum; ++i) {
                File file = files.get(i);
                try {
                    InputStream input = null;
                    //发送文件名
                    byte[] bt_fileName = file.getName().getBytes();
                    int fileName_len = bt_fileName.length;
                    out.write(IntAndBytes.send_instruction_len(Constant.PIECE_FILE_NAME, fileName_len));
                    out.write(bt_fileName);
                    //发送文件的长度
                    int fileLen = (int) file.length();
                    out.write(IntAndBytes.send_instruction_len(Constant.PIECE_FILE, fileLen));
                    //读取文件的内容发送
                    input = new FileInputStream(file);
                    byte[] data = new byte[Constant.BUFFER_SIZE];

                    //int already_send_data=0;
                    int limitRead = 0;
                    if (fileLen > Constant.BUFFER_SIZE) {
                        limitRead = Constant.BUFFER_SIZE;
                    } else {
                        limitRead = fileLen;
                    }
                    int readLen = 0;
                    while (true) {
                        int len = input.read(data, 0, limitRead);
                        //已经读完
                        if (len == -1) {
                            break;
                        }
                        out.write(data, 0, len);
                        // already_send_len += limitRead;   //总体进度
                        readLen += len;   //单个文件的进度
                        limitRead = fileLen - readLen;
                        if (limitRead > Constant.BUFFER_SIZE) {
                            limitRead = Constant.BUFFER_SIZE;
                        } else if (limitRead <= 0) {
                            //发送完成
                            break;
                        }
                    }
                    //关闭文件流
                    input.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    //文件发送异常
                    SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                            "对 " + partner_phoneName + " 的数据发送异常"
                    );
                    return;
                }
            }
            //发送完毕
            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                    "对 " + partner_phoneName + " 的数据发送完毕"
            );
        }
    }


    //发送给UI进程
    void SendMessage(int what, int arg1, int arg2, Object obj) {
        if (handler != null) {
            Message.obtain(handler, what, arg1, arg2, obj).sendToTarget();
        }
    }


    //以下是自动生成的Getter和Setter方法

    public EncodeFile getLocalEncodeFile() {
        return LocalEncodeFile;
    }

    public void setLocalEncodeFile(EncodeFile localEncodeFile) {
        LocalEncodeFile = localEncodeFile;
    }
}


