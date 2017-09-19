package nc;

import java.util.concurrent.Semaphore;

/**
 * 此类用于网络编码相关操作
 * 对JNI函数需要互斥访问，因此需要上锁与解锁
 * Created by Administrator on 2017/6/14 0014.
 */

public class NCUtil {
    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    //为了避免两个线程同时进入jni操作，这里需要引入锁 信号量机制
    public final static Semaphore NC_SEMAPHORE = new Semaphore(1);
    //使用jni方法前
    //.acquire();
    //使用后
    //.release();

    //申请使用nc
    public static void nc_acquire(){
        try {
            NCUtil.NC_SEMAPHORE.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    //使用完毕释放掉
    public static void nc_release(){
        NCUtil.NC_SEMAPHORE.release();
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    //编码函数
    public static native byte[][] Encode(byte[] buffer_, int N, int K, int nLen);

    //随机再编码函数,nLength为编码文件的总长（1+K+len)
    public static native byte[][] Reencode(byte[][] buffer, int nPart, int nLength, int outputNum);

    //里德所罗门再编码 再编码矩阵的构造不同 RS_Flag为基数
    public static native byte[][] RSReencode(byte[][] buffer, int RS_Flag, int nPart, int nLength, int outputNum);

    //解码函数
    public static native byte[][] Decode(byte[][] buffer, int nPart, int nLength);

    //求秩函数
    public static native int getRank(byte[][] matrix, int nRow, int nCol);
}
