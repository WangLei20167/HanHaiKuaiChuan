package appData;

import android.content.Context;
import android.os.Environment;

import utils.MyFileUtils;


/**
 * 用以定义全局变量
 * Created by Administrator on 2017/7/22 0022.
 */

public class GlobalVar {
    private static String dataFolderPath;
    private static String FileRevPath;
    private static String TempPath;
    private static Context mainContext;   //主活动的context

    //初始化变量
    public static void initial(Context context) {
        mainContext = context;
        dataFolderPath = MyFileUtils.creatFolder(Environment.getExternalStorageDirectory().getPath(), "hanhaikuaichuan");
        TempPath = MyFileUtils.creatFolder(dataFolderPath, "Temp");  //创建文件暂存的目录
        FileRevPath = MyFileUtils.creatFolder(dataFolderPath, "FileRev");
    }

    public static String getDataFolderPath() {
        return dataFolderPath;
    }

    public static String getFileRevPath() {
        return FileRevPath;
    }

    public static Context getMainContext() {
        return mainContext;
    }

    public static String getTempPath() {
        return TempPath;
    }
}
