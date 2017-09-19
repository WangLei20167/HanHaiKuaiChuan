package fileSlices;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import com.thoughtworks.xstream.converters.reflection.FieldDictionary;
import com.thoughtworks.xstream.converters.reflection.SortableFieldKeySorter;
import com.thoughtworks.xstream.converters.reflection.Sun14ReflectionProvider;
import com.thoughtworks.xstream.io.xml.DomDriver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import appData.GlobalVar;
import utils.LocalInfor;
import utils.MyFileUtils;

/**
 * Created by Administrator on 2017/7/22 0022.
 */

@XStreamAlias("EncodeFile")
public class EncodeFile {
    //主属性
    @XStreamAsAttribute
    private String fileName;

    private int nK;    //子代编码文件数目
    //分成的大的部分文件
    private int CurrentParts = 0;
    private int TotalParts = 0;
    //小的编码文件片
    private int currentSmallPiece = 0;
    private int totalSmallPiece = 0;
    //文件校验长度
    private int rightFileLen1 = 0;
    private int rightFileLen2 = 0;
    //文件片的信息
    @XStreamAlias("PieceFileInfor")
    private List<PieceFile> pieceFileList = new ArrayList<PieceFile>();

    //隐藏以下字段
    @XStreamOmitField
    private String storagePath;                    //文件存储路径
    @XStreamOmitField
    private static String xmlFileName = "xml.txt";  //配置文件的名称

    public EncodeFile(String fileName, int nK) {
        this.fileName = fileName;
        this.nK = nK;

        String folderName = fileName.substring(0, fileName.lastIndexOf("."));   //获取不含后缀的文件名,作为文件夹名字
        //storagePath = GlobalVar.getTempPath() + File.separator + folderName;
        //如果存储路径已经存在，则先删除
//        File folderPath = new File(storagePath);
//        if (folderPath.exists()) {
//            MyFileUtils.deleteAllFile(storagePath, true);
//        }
        //建立存储路径
        storagePath = MyFileUtils.creatFolder(GlobalVar.getTempPath(), folderName);
    }


    //对文件进行分片
    public void cutFile(File originFile) {
//        if(!originFile.exists()){
//            //文件不存在(不可能）
//            return;
//        }
        int fileLen = (int) originFile.length();
        int file_piece_len = 10 * 1024 * 1024;  //若是大于10M的文件，对文件进行分片，每片10M
        final int piece_num = fileLen / file_piece_len + (fileLen % file_piece_len != 0 ? 1 : 0);
        int rest_len = fileLen - file_piece_len * (piece_num - 1);  //最后一片的长度

        //获取文件流
        InputStream in;
        try {
            in = new FileInputStream(originFile);
        } catch (FileNotFoundException e) {
            //读文件出错
            e.printStackTrace();
            return;
        }

        //分成的大文件片，暂存地址
        String dataTempPath = MyFileUtils.creatFolder(storagePath, "dataTemp");
        ArrayList<File> temp_files = new ArrayList<File>();
        //读取文件到片文件
        for (int i = 0; i < piece_num; ++i) {
            if (i == (piece_num - 1)) {
                //创建一个文件用于写入这一片数据
                File piece_file = MyFileUtils.splitFile(in, dataTempPath, LocalInfor.getCurrentTime("MMddHHmmssSSS") + ".piece", rest_len);
                temp_files.add(piece_file);
                try {
                    //关闭文件流
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            } else {
                //创建一个文件用于写入这一片数据
                File piece_file = MyFileUtils.splitFile(in, dataTempPath, LocalInfor.getCurrentTime("MMddHHmmssSSS") + ".piece", file_piece_len);
                temp_files.add(piece_file);
            }
        }
        //为两个校验长度赋值
        //前几个部分文件校验长度
        int perLen = file_piece_len / nK + (file_piece_len % nK != 0 ? 1 : 0);
        rightFileLen1 = 1 + nK + perLen;
        //最后一个部分文件校验长度
        int rest_perLen = rest_len / nK + (rest_len % nK != 0 ? 1 : 0);
        rightFileLen2 = 1 + nK + rest_perLen;

        //对大文件片再进行分片
        for (int i = 0; i < piece_num; ++i) {
            int rightFileLen;
            if (i == (piece_num - 1)) {
                rightFileLen = rightFileLen2;
            } else {
                rightFileLen = rightFileLen1;
            }
            PieceFile pieceFile = new PieceFile(storagePath, i + 1, nK, rightFileLen);
            File file = temp_files.get(i);
            pieceFile.encodeFile(file);
            pieceFile.re_encodeFile();
            //添加进List
            pieceFileList.add(pieceFile);
        }
        MyFileUtils.deleteAllFile(dataTempPath, true);
        //设置变量
        CurrentParts = piece_num;
        TotalParts = piece_num;
        currentSmallPiece = piece_num * nK;
        totalSmallPiece = currentSmallPiece;
        //把配置存入xml文件
        object2xml();

    }

    //本方法用于接收完一次数据后调用
    //更新本地数据配置信息
    public void solveFileChange() {
        //重新再编码并获取现在编码数据片数
        int smallPieceNum = 0;
        for (PieceFile pieceFile : pieceFileList) {
            if (pieceFile.isFileChanged()) {
                pieceFile.re_encodeFile();
            }
            smallPieceNum += pieceFile.getCurrentFileNum();
        }
        currentSmallPiece = smallPieceNum;
    }

    //恢复文件
    public boolean recoveryFile() {
        //合并后的文件存储路径
        String outFilePath = storagePath + File.separator + fileName;
        File originFile = new File(outFilePath);
        if (originFile.exists()) {
            //已经解码过了
            return true;
        }
        if (CurrentParts != TotalParts) {
            return false;
        }
        if (currentSmallPiece != totalSmallPiece) {
            return false;
        }
        File[] files = new File[TotalParts];
        for (PieceFile pieceFile : pieceFileList) {
            int pieceNo = pieceFile.getPieceNo();
            File file = pieceFile.getOriginPFile();
            if (file == null) {
                return false;
            } else {
                files[pieceNo - 1] = file;
            }
        }
        for (int i = 0; i < TotalParts; ++i) {
            if (files[i] == null) {
                return false;
            }
        }
        //合并文件
        //String outFilePath = storagePath + File.separator + fileName;
        MyFileUtils.mergeFiles(outFilePath, files);
        return true;
    }

    //把对象保存在xml文件中
    public void object2xml() {
        //设置xml字段顺序
        SortableFieldKeySorter sorter = new SortableFieldKeySorter();
        sorter.registerFieldOrder(EncodeFile.class,
                new String[]{
                        "fileName",
                        "nK",
                        "CurrentParts",
                        "TotalParts",
                        "currentSmallPiece",
                        "totalSmallPiece",
                        "rightFileLen1",
                        "rightFileLen2",
                        "pieceFileList",
                        "storagePath",
                        "xmlFileName"
                });
        sorter.registerFieldOrder(PieceFile.class,
                new String[]{
                        "pieceNo",
                        "currentFileNum",
                        "nK",
                        "rightFileLen",
                        "coeffMatrix",
                        "pieceFilePath",
                        "encodeFilePath",
                        "re_encodeFilePath",
                        "fileChanged"
                });
        //XStream xStream = new XStream(new DomDriver("UTF-8"));
        XStream xStream = new XStream(new Sun14ReflectionProvider(new FieldDictionary(sorter)));
        xStream.setMode(XStream.NO_REFERENCES);
        //使用注解
        xStream.processAnnotations(EncodeFile.class);
        xStream.processAnnotations(PieceFile.class);
        //转化为String，并保存入文件
        String xml = xStream.toXML(this);
        MyFileUtils.writeToFile(storagePath, xmlFileName, xml.getBytes());
    }

    /**
     * 从已有的EncodeFile变量，clone一个只含基本信息的新对象
     * 当client接收到server数据xml配置信息，发现本地没有任何数据片信息试，调用
     * @param encodeFile
     * @return
     */
    public static EncodeFile clone(EncodeFile encodeFile) {
        //从encodeFile中获取需要clone的信息
        String fileName = encodeFile.fileName;
        int nK = encodeFile.getnK();
        //int TotalParts = encodeFile.TotalParts;
        //int totalSmallPiece = encodeFile.totalSmallPiece;
        //clone
        EncodeFile newEncodeFile = new EncodeFile(fileName, nK);
        newEncodeFile.TotalParts = encodeFile.TotalParts;
        newEncodeFile.totalSmallPiece = encodeFile.totalSmallPiece;
        newEncodeFile.rightFileLen1 = encodeFile.rightFileLen1;
        newEncodeFile.rightFileLen2 = encodeFile.rightFileLen2;
        newEncodeFile.object2xml();    //把配置信息写入文件
        return newEncodeFile;
    }

    /**
     * 从xml文件读出object
     *
     * @param xml_obj        xml文件的存储路径或是xml文件对象
     * @param recoverAllPath 是否恢复文件路径的控制
     * @return
     */
    public static EncodeFile xml2object(Object xml_obj, boolean recoverAllPath) {
        //读取xml配置文件
        byte[] bt_xml = MyFileUtils.readFile(xml_obj);
        if (bt_xml == null) {
            return null;
        }
        String xml = new String(bt_xml);
        XStream xStream = new XStream(new DomDriver("UTF-8"));
        //使用注解
        xStream.processAnnotations(EncodeFile.class);
        xStream.processAnnotations(PieceFile.class);
        //这个blog标识一定要和Xml中的保持一直，否则会报错
        xStream.alias("EncodeFile", EncodeFile.class);
        EncodeFile encodeFile = null;
        try {
            encodeFile = (EncodeFile) xStream.fromXML(xml);
        } catch (Exception e) {
            e.printStackTrace();
            //解析出错
            return null;
        }

        //恢复没有写入xml的属性
        //恢复pieceFile的nK值
        for (PieceFile pieceFile : encodeFile.getPieceFileList()) {
            pieceFile.setnK(encodeFile.getnK());
        }
        //是否恢复所有文件路径
        if (recoverAllPath) {
            //恢复总存储路径
            String fileName = encodeFile.fileName;
            String folderName = fileName.substring(0, fileName.lastIndexOf("."));   //获取不含后缀的文件名,作为文件夹名字
            String storagePath = GlobalVar.getTempPath() + File.separator + folderName;
            encodeFile.setStoragePath(storagePath);
            for (PieceFile pieceFile : encodeFile.getPieceFileList()) {
                //PieceFile的一级目录
                String pieceFilePath = storagePath + File.separator + pieceFile.getPieceNo();
                pieceFile.setPieceFilePath(pieceFilePath);
                //两个二级目录
                String encodeFilePath = pieceFilePath + File.separator + "encodeFiles";
                pieceFile.setEncodeFilePath(encodeFilePath);
                String re_encodeFilePath = pieceFilePath + File.separator + "re_encodeFile";
                pieceFile.setRe_encodeFilePath(re_encodeFilePath);
            }
        }
        return encodeFile;
    }

    /**
     * 以下是自动生成的Getter和Setter方法
     */
    public int getCurrentParts() {
        return CurrentParts;
    }

    public void setCurrentParts(int currentParts) {
        CurrentParts = currentParts;
    }

    public int getCurrentSmallPiece() {
        return currentSmallPiece;
    }

    public void setCurrentSmallPiece(int currentSmallPiece) {
        this.currentSmallPiece = currentSmallPiece;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public int getnK() {
        return nK;
    }

    public void setnK(int nK) {
        this.nK = nK;
    }

    public List<PieceFile> getPieceFileList() {
        return pieceFileList;
    }

    public void setPieceFileList(List<PieceFile> pieceFileList) {
        this.pieceFileList = pieceFileList;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public int getTotalParts() {
        return TotalParts;
    }

    public void setTotalParts(int totalParts) {
        TotalParts = totalParts;
    }

    public int getTotalSmallPiece() {
        return totalSmallPiece;
    }

    public void setTotalSmallPiece(int totalSmallPiece) {
        this.totalSmallPiece = totalSmallPiece;
    }

    public int getRightFileLen1() {
        return rightFileLen1;
    }

    public void setRightFileLen1(int rightFileLen1) {
        this.rightFileLen1 = rightFileLen1;
    }

    public int getRightFileLen2() {
        return rightFileLen2;
    }

    public void setRightFileLen2(int rightFileLen2) {
        this.rightFileLen2 = rightFileLen2;
    }

}
