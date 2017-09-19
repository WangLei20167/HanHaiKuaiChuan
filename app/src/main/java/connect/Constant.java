package connect;

/**
 * Created by Administrator on 2017/7/24 0024.
 */

public class Constant {
    //定义AP密码
    public final static String AP_PASS_WORD = "123456789";
    //作为Server时的监听地址与端口
    public final static String TCP_ServerIP = "192.168.43.1";
    public final static int TCP_ServerPORT = 10000;

    //设置socket读写缓存的大小
    public final static int BUFFER_SIZE = 1024; //设置缓存区为1K

    //约定的指令编号 instruction
    public final static int PHONE_NAME = 0;
    public final static int XML_FILE = 1;
    public final static int PIECE_FILE_NAME = 2;  //其中包含pieceNo
    public final static int PIECE_FILE = 3;
    public final static int SEND_FINISH = 4;     //告诉对方自己发送完毕
    public final static int END_FLAG = 5;


    //socket一端断开的标志
    public final static String END_STRING = "I am leaving";
    //socket对方发送完的标识
    public final static String SEND_FINISH_STRING = "I send finish";

}
