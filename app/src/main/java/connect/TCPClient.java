package connect;

import android.content.Context;
import android.os.Handler;
import android.os.Message;

import com.example.administrator.hanhaikuaichuan.MainActivity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import appData.GlobalVar;
import fileSlices.EncodeFile;
import fileSlices.PieceFile;
import msg.MsgValue;
import utils.IntAndBytes;
import utils.LocalInfor;
import utils.MyFileUtils;

/**
 * Created by Administrator on 2017/7/26 0026.
 */

public class TCPClient {
    private Socket socket = null;
    private DataInputStream in = null;   //接收
    private DataOutputStream out = null; //发送
    private Handler handler = null;
    //private Context context=null;
    //本地的编码数据
    private EncodeFile LocalEncodeFile = null;
    //接收线程
    private RevASendThread revASendThread = null;
    //wifi
    private WifiAdmin wifiAdmin = null;

    //构造函数
    public TCPClient(Handler handler, Context context) {
        this.handler = handler;
        //this.context=context;
        wifiAdmin = new WifiAdmin(context);
    }

    //创建socket连接
    public void connectServer() {
        Thread connectWiFiThread = new Thread(new Runnable() {
            @Override
            public void run() {
                //此处做打开wifi的操作
                wifiAdmin.openWifi();
                wifiAdmin.searchWifi(GlobalVar.getmSSID());
                wifiAdmin.addNetwork(
                        wifiAdmin.CreateWifiInfo(GlobalVar.getmSSID(), Constant.AP_PASS_WORD, 3)
                );
                //等待连接的时间为10s
                // Starting time.
                long startMili = System.currentTimeMillis();
                while (!wifiAdmin.isWifiConnected()) {
                    if (wifiAdmin.currentConnectSSID().equals(GlobalVar.getmSSID())) {
                        break;
                    }
                    //等待连接
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    // Ending time.
                    long endMili = System.currentTimeMillis();
                    int time = (int) ((endMili - startMili) / 1000);
                    if (time > 10) {
                        SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                                "连接指定wifi失败，请重试"
                        );
                        return;
                    }
                }
                SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                        "连接wifi成功"
                );
                try {
                    //若是socket不为空
                    if (socket != null) {
                        socket.close();
                        socket = null;
                    }
                    //实现一个socket创建3秒延迟的作用
                    long startTime = System.currentTimeMillis();
                    while (true) {
                        try {
                            socket = new Socket(Constant.TCP_ServerIP, Constant.TCP_ServerPORT);
                            break;
                        } catch (IOException e) {
                            e.printStackTrace();
                            long endTime = System.currentTimeMillis();
                            if ((endTime - startTime) > 3000) {
                                throw new IOException();
                            } else {
                                //等待0.1秒重连
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e1) {
                                    e1.printStackTrace();
                                }
                            }
                        }
                    }

                    socket.setTcpNoDelay(true);
                    in = new DataInputStream(socket.getInputStream());     //接收
                    out = new DataOutputStream(socket.getOutputStream());//发送

                    //在此发送本机型号
                    String phoneName = LocalInfor.getPhoneModel();
                    byte[] send_phoneName = phoneName.getBytes();
                    int len = send_phoneName.length;
                    out.write(IntAndBytes.send_instruction_len(Constant.PHONE_NAME, len));   //发送指令长度
                    out.write(send_phoneName);
                } catch (IOException e) {
                    e.printStackTrace();
                    SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, "连接Socket失败");
                    return;
                }
                //连接成功
                //开启接收线程
                SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, "连接Socket成功");
                revASendThread = new RevASendThread();
                revASendThread.start();
                return;
            }
        });
        connectWiFiThread.setName("连接wifi并建立socket线程");
        connectWiFiThread.start();
        System.out.println(connectWiFiThread.getName() + " 已启动");
    }

    //关闭socket，断开连接
    public void disconnectServer() {
        try {
            //关闭前向服务端发送退出信号
            byte[] bt_end_string = Constant.END_STRING.getBytes();
            out.write(IntAndBytes.send_instruction_len(Constant.END_FLAG, bt_end_string.length));
            out.write(bt_end_string);

            revASendThread.kk = false;
            //关闭流
            out.close();
            in.close();
            //关闭Socket
            socket.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    //处理接收与发送数据
    class RevASendThread extends Thread {
        //对方的手机名
        private String partner_phoneName = "";
        //标志本地向对方发送的数据是否发送完毕
        private volatile boolean sendFinish = false;
        //标志接收循环是否该退出了
        private boolean kk = true;

        public RevASendThread() {
            this.setName("TCPClient接收发送线程");
            System.out.println(this.getName() + " 已启动");
        }

        @Override
        public void run() {
            byte[] getBytes = new byte[5];
            int pieceNo = 0;
            String pieceFileName = "";
            while ((socket != null) && socket.isConnected() && kk) {
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
                                    "已连接到 " + partner_phoneName
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
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        while (!sendFinish) {
                                            try {
                                                Thread.sleep(100);
                                            } catch (InterruptedException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                        //断开连接
                                        disconnectServer();
                                        SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                                                "连接已断开"
                                        );
                                        LocalEncodeFile.solveFileChange();
                                    }
                                }).start();
                            }
                            break;
                        //socket的另一方已经关闭socket
                        case Constant.END_FLAG:
                            byte[] bt_endFlag = (byte[]) limitReadBytes(readLen, "");
                            String endFlag = new String(bt_endFlag);
                            if (endFlag.equals(Constant.END_STRING)) {
                                //对方已经退出，己方关闭socket
                                kk = false;
                                //关闭流
                                out.close();
                                in.close();
                                //关闭Socket
                                socket.close();
                                LocalEncodeFile.solveFileChange();
                            }
                            break;
                        default:
                            //若执行到此处，则说明传输出错了
                            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                                    "Server 端接收数据出错"
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
                //更新系数成功，本地数据片+1
                LocalEncodeFile.setCurrentSmallPiece(LocalEncodeFile.getCurrentSmallPiece() + 1);
            } catch (Exception e) {
                //接收文件出错
                e.printStackTrace();
            } finally {
                //就算系数更新失败，这部分也会执行
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
            //对方文件数目信息
            int currentSmallPiece = itsEncodeFile.getCurrentSmallPiece();
            int totalSmallPiece = itsEncodeFile.getTotalSmallPiece();
            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                    partner_phoneName + " 已有/共需文件个数：" + currentSmallPiece + "/" + totalSmallPiece
            );
            //在本地查找
            LocalEncodeFile = null;
            String fileName = itsEncodeFile.getFileName();
            String folderName = fileName.substring(0, fileName.lastIndexOf("."));
            ArrayList<File> folders = MyFileUtils.getListFolders(GlobalVar.getTempPath());
            for (File folder : folders) {
                if (folder.getName().equals(folderName)) {
                    //恢复对本地数据的控制
                    String xml_file_path = GlobalVar.getTempPath() + File.separator + folderName + File.separator + "xml.txt";
                    LocalEncodeFile = EncodeFile.xml2object(xml_file_path, true);
                    break;
                }
            }
            if (LocalEncodeFile == null) {
                //本地没有任何数据
                LocalEncodeFile = EncodeFile.clone(itsEncodeFile);
            }
            SendMessage(MsgValue.SET_FILE_NAME, 0, 0, LocalEncodeFile.getFileName());
            SendMessage(MsgValue.SET_CUR_TOTAL_TV,
                    LocalEncodeFile.getCurrentSmallPiece(),
                    LocalEncodeFile.getTotalSmallPiece(),
                    null
            );

            //发送配置文件xml_file
            String xml_file_path = LocalEncodeFile.getStoragePath() + File.separator + "xml.txt";
            byte[] bt_xml_file = MyFileUtils.readFile(xml_file_path);
            int file_len = bt_xml_file.length;
            try {
                out.write(IntAndBytes.send_instruction_len(Constant.XML_FILE, file_len));
                out.write(bt_xml_file);
            } catch (IOException e) {
                e.printStackTrace();
            }

            //正常返回口：1
            if (LocalEncodeFile.getCurrentSmallPiece() == 0) {
                //本地数据是新建立的，没有任何数据
                sendFinish = true;
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


            //正常返回口：2
            if (currentSmallPiece == totalSmallPiece) {
                //说明对方已经拥有所有的数据，不用再发送
                //本地数据是新建立的，没有任何数据
                sendFinish = true;
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

            //正常返回口：3
            if (currentSmallPiece == totalSmallPiece) {
                //说明对方已经拥有所有的数据，不用再发送
                //本地数据是新建立的，没有任何数据
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

    void SendMessage(int what, int arg1, int arg2, Object obj) {
        if (handler != null) {
            Message.obtain(handler, what, arg1, arg2, obj).sendToTarget();
        }
    }


}
