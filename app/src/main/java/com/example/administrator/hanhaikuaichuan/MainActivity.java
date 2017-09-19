package com.example.administrator.hanhaikuaichuan;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.nononsenseapps.filepicker.FilePickerActivity;
import com.nononsenseapps.filepicker.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import appData.GlobalVar;
import connect.APHelper;
import connect.Constant;
import connect.TCPClient;
import connect.TCPServer;
import connect.WifiAdmin;
import fileSlices.EncodeFile;
import msg.MsgValue;
import myDialog.SelectFileDialog;
import myDialog.SettingDialog;
import utils.IntAndBytes;
import utils.MyFileUtils;

public class MainActivity extends AppCompatActivity {

    //声明控件
    private TextView tv_promptMsg;
    private TextView tv_fileName;
    private TextView tv_cur_total;
    private Button bt_server;
    private Button bt_client;
    private Button bt_selectFile;

    //用于管理TCPServer和TCPClient
    private TCPServer mTCPServer = null;
    private TCPClient mTCPClient = null;

    //网络编码
    private int K = 4;
    //选择待发送的编码文件
    private EncodeFile myEncodeFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initialVariable();

        //Server状态
        bt_server = (Button) findViewById(R.id.button_server);
        bt_server.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //解码的测试
                //EncodeFile encodeFile=EncodeFile.xml2object("IMG_20170521_210746",true);
//                new Thread(new Runnable() {
//                    @Override
//                    public void run() {
//                        EncodeFile encodeFile = EncodeFile.xml2object("Never Say Never", true);
//                        SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, encodeFile.getFileName() + " 开始解码...");
//                        if (encodeFile.recoveryFile()) {
//                            SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, encodeFile.getFileName() + " 解码成功");
//                        }
//                    }
//                }).start();
                if (myEncodeFile == null) {
                    bt_selectFile.performClick();
                    return;
                }
                mTCPServer.StartServer(myEncodeFile);
            }
        });

        //Client状态
        bt_client = (Button) findViewById(R.id.button_client);
        bt_client.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mTCPClient.connectServer();
            }
        });

        //选择文件
        bt_selectFile = (Button) findViewById(R.id.button_selectFile);
        bt_selectFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<File> folders = MyFileUtils.getListFolders(GlobalVar.getTempPath());
                if (folders.size() == 0) {
                    openSelectFile();
                } else {
                    showSelectFileDialog();
                }
            }
        });

    }

    //在此做初始化变量操作
    public void initialVariable() {
        //检查权限
        checkRequiredPermission(this);
        //初始化全局变量
        GlobalVar.initial(this);
        //初始化textview变量
        tv_promptMsg = (TextView) findViewById(R.id.tv_promptMsg);
        tv_promptMsg.setMovementMethod(ScrollingMovementMethod.getInstance());
        tv_fileName = (TextView) findViewById(R.id.textView_fileName);
        tv_cur_total = (TextView) findViewById(R.id.textView_cur_total);
        //文件选择器开始目录   取
        SharedPreferences pref = getSharedPreferences("data", MODE_PRIVATE);
        startPath = pref.getString("startPath", Environment.getExternalStorageDirectory().getPath());
        //
        mTCPServer = new TCPServer(handler, this);
        mTCPClient = new TCPClient(handler, this);
    }

    //用来处理文件选择器
    private static final int FILE_CODE = 0;
    private String startPath;

    public void openSelectFile() {
        //打开文件选择器
        Intent i = new Intent(this, FilePickerActivity.class);
        //单选
        i.putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
        //多选
        //i.putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, true);
        i.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, false);
        i.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_FILE);
        //设置开始时的路径
        i.putExtra(FilePickerActivity.EXTRA_START_PATH, startPath);
        //i.putExtra(FilePickerActivity.EXTRA_START_PATH, "/storage/emulated/0/DCIM");
        startActivityForResult(i, FILE_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == FILE_CODE && resultCode == Activity.RESULT_OK) {
            // Use the provided utility method to parse the result
            List<Uri> files = Utils.getSelectedFilesFromResult(intent);
            ArrayList<File> fileList = new ArrayList<File>();
            for (Uri uri : files) {
                File file = Utils.getFileForUri(uri);
                // Do something with the result...
                fileList.add(file);
            }
            //编码该文件
            final File file = fileList.get(0);
            String s = file.getParent();
            //如果有改变则写入新的
            if (!s.equals(startPath)) {
                //存
                SharedPreferences.Editor editor = getSharedPreferences("data", MODE_PRIVATE).edit();
                editor.putString("startPath", s);
                editor.apply();
                startPath = s;
            }

            //送去编码
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String fileName = file.getName();
                    String folderName = fileName.substring(0, fileName.lastIndexOf("."));   //获取不含后缀的文件名,作为文件夹名字
                    ArrayList<File> folders = MyFileUtils.getListFolders(GlobalVar.getTempPath());
                    EncodeFile encodeFile = null;
                    for (File folder : folders) {
                        if (folder.getName().equals(folderName)) {
                            String storagePath = GlobalVar.getTempPath() + File.separator + folderName;
                            String xml_file_path = storagePath + File.separator + "xml.txt";
                            encodeFile = EncodeFile.xml2object(xml_file_path, true);
                            if (encodeFile != null) {
                                SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0,
                                        folderName + " 编码文件已存在，若需重新生成，则删除后重试");
                            } else {
                                SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, folderName + " 文件损坏");
                                MyFileUtils.deleteAllFile(folder.getPath(), true);
                            }
                            break;
                        }
                    }
                    SendMessage(MsgValue.SET_FILE_NAME, 0, 0, fileName);
                    if (encodeFile == null) {
                        encodeFile = new EncodeFile(fileName, K);
                        SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, fileName + " 正在编码...");
                        encodeFile.cutFile(file);
                         /*发送给UI，告知编码完成*/
                        SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, fileName + " 编码完成");
                    }
                    myEncodeFile = encodeFile;
                    int currentSmallPiece = encodeFile.getCurrentSmallPiece();
                    int totalSmallPiece = encodeFile.getTotalSmallPiece();
                    SendMessage(MsgValue.SET_CUR_TOTAL_TV, currentSmallPiece, totalSmallPiece, null);
                }
            }).start();
        }
    }


    /**
     * 处理各个类发来的UI请求消息
     */
    public Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MsgValue.TELL_ME_SOME_INFOR:
                    String infor = msg.obj.toString();
                    refreshLogView(infor);
                    break;
                case MsgValue.SET_FILE_NAME:
                    String file_name = msg.obj.toString();
                    tv_fileName.setText("文件名：" + file_name);
                    tv_cur_total.setText("已有/共需文件片数：");
                    break;
                case MsgValue.SET_CUR_TOTAL_TV:
                    int curNum = msg.arg1;
                    int totalNum = msg.arg2;
                    tv_cur_total.setText("已有/共需文件片数：" + curNum + "/" + totalNum);
                    break;
                default:
                    break;
            }
            super.handleMessage(msg);
        }
    };

    //本类发送消息的方法
    private void SendMessage(int what, int arg1, int arg2, Object obj) {
        if (handler != null) {
            Message.obtain(handler, what, arg1, arg2, obj).sendToTarget();
        }
    }

    //信息提示框
    private void refreshLogView(String msg) {
        int lineCount = tv_promptMsg.getLineCount();
        if (lineCount > 200) {
            tv_promptMsg.setText("提示框内容超过200行，清空一次");
            lineCount = 2;
        }
        if ((lineCount == 1) && (tv_promptMsg.getText().toString().equals(""))) {
            tv_promptMsg.append(msg);
        } else {
            tv_promptMsg.append("\n" + msg);
        }

        int offset = (lineCount + 3) * tv_promptMsg.getLineHeight();
        if (offset > tv_promptMsg.getHeight()) {
            tv_promptMsg.scrollTo(0, offset - tv_promptMsg.getHeight());
        }
    }


    //点击两次back退出程序
    private long mExitTime;

    @Override
    public void onBackPressed() {
        if ((System.currentTimeMillis() - mExitTime) > 2000) {
            Toast.makeText(this, "再按一次退出程序", Toast.LENGTH_SHORT).show();
            mExitTime = System.currentTimeMillis();

        } else {
            //退出前的处理操作
            //执行退出操作,并释放资源
            finish();
            //Dalvik VM的本地方法完全退出app
            Process.killProcess(Process.myPid());    //获取PID
            System.exit(0);   //常规java、c#的标准退出法，返回值为0代表正常退出
        }
    }

    /**
     * 以下处理两个弹窗
     */
    /**
     * 选择文件弹窗
     */
    private void showSelectFileDialog() {
        final SelectFileDialog selectFileDialog = new SelectFileDialog(MainActivity.this, GlobalVar.getTempPath());
        selectFileDialog.setOnPositiveListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String folderName = selectFileDialog.getResult_folder();
                if (folderName.equals("")) {
                    selectFileDialog.dismiss();
                    openSelectFile();
                } else {
                    selectFileDialog.dismiss();
                    String storagePath = GlobalVar.getTempPath() + File.separator + folderName;
                    String xml_file_path = storagePath + File.separator + "xml.txt";
                    EncodeFile encodeFile = EncodeFile.xml2object(xml_file_path, true);
                    if (encodeFile != null) {
                        myEncodeFile = encodeFile;
                        String fileName = encodeFile.getFileName();
                        SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, "已选择 " + folderName);
                        //设置文件名以及子文件数目
                        SendMessage(MsgValue.SET_FILE_NAME, 0, 0, fileName);
                        int currentSmallPiece = encodeFile.getCurrentSmallPiece();
                        int totalSmallPiece = encodeFile.getTotalSmallPiece();
                        SendMessage(MsgValue.SET_CUR_TOTAL_TV, currentSmallPiece, totalSmallPiece, null);
                    } else {
                        SendMessage(MsgValue.TELL_ME_SOME_INFOR, 0, 0, folderName + " 文件损坏");
                        String folderPath = GlobalVar.getTempPath() + File.separator + folderName;
                        MyFileUtils.deleteAllFile(folderPath, true);
                        return;
                    }
                }
            }
        });
        selectFileDialog.setOnNegativeListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectFileDialog.dismiss();
            }
        });
        selectFileDialog.show();
    }

    /**
     * 设置K值弹窗
     */
    private void showSettingDialog() {
        final SettingDialog settingDialog = new SettingDialog(MainActivity.this);
        settingDialog.initNum(K);
        settingDialog.setOnPositiveListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int k = settingDialog.getEt_K();
                //dosomething youself
                if (k > 10 || k < 3) {
                    Toast.makeText(MainActivity.this, "K值不合适，取值范围3-10", Toast.LENGTH_SHORT).show();
                    return;
                }
                //赋值
                K = k;
                settingDialog.dismiss();
            }
        });
        settingDialog.setOnNegativeListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingDialog.dismiss();
            }
        });
        settingDialog.show();
    }


    /**
     * 以下处理菜单选项
     *
     * @param menu
     * @return
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                //设置K值
                showSettingDialog();
                break;
            case R.id.action_openAppFolder:
                //打开应用文件夹
                //String path="/storage/emulated/0/DCIM";
                Intent intent = new Intent(MainActivity.this, FilesListViewActivity.class);
                //intent.putExtra("data_path",path);
                intent.putExtra("data_path", GlobalVar.getDataFolderPath());
                startActivity(intent);
                break;
            case R.id.action_description:
                Toast.makeText(this, "显示软件信息", Toast.LENGTH_SHORT).show();
                break;
            default:
        }
        return true;
    }


    /**
     * 处理权限问题
     */
    private static final String[] permissionsArray = new String[]{
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_SETTINGS,
    };
    //还需申请的权限列表
    private List<String> permissionsList = new ArrayList<String>();
    //申请权限后的返回码
    private static final int REQUEST_CODE_ASK_PERMISSIONS = 1;

    //检查和申请权限
    private void checkRequiredPermission(final Activity activity) {
        for (String permission : permissionsArray) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(permission);
            }
        }
        //若是permissionsList为空，则说明所有权限已用
        if (permissionsList.size() == 0) {
            return;
        }
        ActivityCompat.requestPermissions(activity, permissionsList.toArray(new String[permissionsList.size()]), REQUEST_CODE_ASK_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_ASK_PERMISSIONS:
                for (int i = 0; i < permissions.length; i++) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        //Toast.makeText(MainActivity.this, "做一些申请成功的权限对应的事！" + permissions[i], Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "权限被拒绝： " + permissions[i], Toast.LENGTH_SHORT).show();
                    }
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }


}
