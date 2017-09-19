package myDialog;

import android.app.Dialog;
import android.content.Context;
import android.text.TextPaint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;


import com.example.administrator.hanhaikuaichuan.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


import utils.MyFileUtils;

/**
 * Created by Administrator on 2017/6/12 0012.
 */

public class SelectFileDialog extends Dialog {
    private Button positiveButton, negativeButton;
    private TextView tv_title;
    private List<String> file_list = new ArrayList<String>();
    private List<File> folders = new ArrayList<File>();
    private int folderNum;
    private String filePath;


    private String result_folder = "";
    private ListView listView = null;

    public SelectFileDialog(Context context, String filePath) {
        super(context, R.style.dialog);
        this.filePath = filePath;
        setSelectFileDialog();
    }

    private void setSelectFileDialog() {
        View mView = LayoutInflater.from(getContext()).inflate(R.layout.select_file_dialog, null);
        positiveButton = (Button) mView.findViewById(R.id.positiveButton1);
        negativeButton = (Button) mView.findViewById(R.id.negativeButton1);
        tv_title=(TextView)mView.findViewById(R.id.textView_title);
        //加粗
        TextPaint tp=tv_title.getPaint();
        tp.setFakeBoldText(true);

        folders = MyFileUtils.getListFolders(filePath);
        folderNum = folders.size();

        file_list.add("重选文件进行编码");
        for (File folder : folders) {
            //String folderPath = folder.getPath();
            // String fileName = new String(MyFileUtils.readFile(folderPath, ConstantValue.FILE_NAME_TXT));
            file_list.add(folder.getName());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getContext(), R.layout.file_item, file_list);
        listView = (ListView) mView.findViewById(R.id.file_list_view);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                //listView.setBackgroundColor(Color.parseColor("#6495ED"));
//                    View view1=listView.getSelectedView();
                //view.setBackgroundColor( Color.parseColor("#6495ED") );
                if (position == 0) {
                    result_folder = "";
                    return;
                }
                result_folder = folders.get(position - 1).getName();
               // String folderPath = folders.get(position - 1).getPath();
               // result_fileName = new String(MyFileUtils.readFile(folderPath, ConstantValue.FILE_NAME_TXT));
                //result_fileName=file_list.get(position);
            }
        });

        super.setContentView(mView);
    }


    public String getResult_folder() {
        return result_folder;
    }


    @Override
    public void setContentView(int layoutResID) {
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
    }

    @Override
    public void setContentView(View view) {
    }

    /**
     * 确定键监听器
     *
     * @param listener
     */
    public void setOnPositiveListener(View.OnClickListener listener) {
        positiveButton.setOnClickListener(listener);
    }

    /**
     * 取消键监听器
     *
     * @param listener
     */
    public void setOnNegativeListener(View.OnClickListener listener) {
        negativeButton.setOnClickListener(listener);
    }
}
