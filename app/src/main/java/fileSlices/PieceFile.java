package fileSlices;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import nc.NCUtil;
import utils.IntAndBytes;
import utils.LocalInfor;
import utils.MyFileUtils;

/**
 * Created by Administrator on 2017/7/22 0022.
 */
@XStreamAlias("PieceFile")
public class PieceFile {
    @XStreamAsAttribute
    private int pieceNo;

    private int currentFileNum = 0;
    //是否对每个编码文件设置校对长度？？？
    private int rightFileLen = 0;

    //系数矩阵 用int数组来存储
    private int[][] coeffMatrix = new int[0][0];
    //隐藏以下字段
    @XStreamOmitField
    private int nK;
    @XStreamOmitField
    private String pieceFilePath;
    @XStreamOmitField
    private String encodeFilePath;
    @XStreamOmitField
    private String re_encodeFilePath;

    //用以标志本部分文件是否发生了改变
    @XStreamOmitField
    private boolean fileChanged = false;

    public PieceFile(String path, int pieceNo, int nK, int rightFileLen) {
        this.pieceNo = pieceNo;
        this.nK = nK;
        this.rightFileLen = rightFileLen;
        //创建存储路径
        pieceFilePath = MyFileUtils.creatFolder(path, pieceNo + "");
        //两个二级目录
        encodeFilePath = MyFileUtils.creatFolder(pieceFilePath, "encodeFiles");
        re_encodeFilePath = MyFileUtils.creatFolder(pieceFilePath, "re_encodeFile");
    }

    /**
     * 通过对方的系数矩阵，在本地找到对其有用的数据
     *
     * @param itsCoeffMatrix
     * @param itsFileNum
     * @return
     */
    public File getUsefulFile(int[][] itsCoeffMatrix, int itsFileNum) {
        //对方的系数矩阵
        byte[][] bt_itsCoeff = IntAndBytes.intArray2byteArray(itsCoeffMatrix, itsFileNum, nK);
        if (bt_itsCoeff == null) {
            return null;
        }
        //本地的系数矩阵
        byte[][] bt_myCoeff = IntAndBytes.intArray2byteArray(coeffMatrix, currentFileNum, nK);
        if (bt_myCoeff == null) {
            return null;
        }
        //对方系数矩阵与本地的每一行组成新矩阵，测试其秩
        int Row = itsFileNum + 1;
        byte[][] test_matrix = new byte[Row][nK];
        for (int i = 0; i < itsFileNum; ++i) {
            for (int j = 0; j < nK; ++j) {
                test_matrix[i][j] = bt_itsCoeff[i][j];
            }
        }
        //按行检查数据是否使现有矩阵秩增加
        for (int i = 0; i < currentFileNum; ++i) {
            for (int j = 0; j < nK; ++j) {
                test_matrix[itsFileNum][j] = bt_myCoeff[i][j];
            }
            NCUtil.nc_acquire();
            int rank = NCUtil.getRank(test_matrix, Row, nK);
            NCUtil.nc_release();
            //证明找到了有用的数据
            if (rank == (itsFileNum + 1)) {
                ArrayList<File> re_encodeFileList = MyFileUtils.getList_1_files(re_encodeFilePath);
                File re_encodeFile = null;
                if (re_encodeFileList.size() == 0) {
                    re_encodeFile = re_encodeFile();
                } else {
                    re_encodeFile = re_encodeFileList.get(0);
                    byte[] coeff = new byte[nK];
                    byte[] b = new byte[1];
                    try {
                        FileInputStream stream = new FileInputStream(re_encodeFile);
                        stream.read(b, 0, 1);
                        //读入一行系数矩阵
                        stream.read(coeff, 0, nK);
                        stream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    //判断再编码文件是否对对方有效
                    for (int j = 0; j < nK; ++j) {
                        test_matrix[itsFileNum][j] = coeff[j];
                    }
                    NCUtil.nc_acquire();
                    int rank1 = NCUtil.getRank(test_matrix, Row, nK);
                    NCUtil.nc_release();
                    if (rank1 == (itsFileNum + 1)) {
                        //有效
                    } else {
                        //无效，重新生成再编码文件
                        re_encodeFile = re_encodeFile();
                    }
                }
                return re_encodeFile;
            }
        }
        return null;
    }

    //对方没有本部分文件信息
    public File getUsefulFile() {
        ArrayList<File> re_encodeFileList = MyFileUtils.getList_1_files(re_encodeFilePath);
        File re_encodeFile = null;
        if (re_encodeFileList.size() == 0) {
            re_encodeFile = re_encodeFile();
        } else {
            re_encodeFile = re_encodeFileList.get(0);
        }
        return re_encodeFile;
    }

    /**
     * 用于接收到文件后，更新系数矩阵
     *
     * @param nc_file
     */
    public void updateCoeffMat(File nc_file) throws Exception {
        //先要检查nc_file是否正确校验
        //是否做文件长度校验？？
        int fileLen = (int) nc_file.length();
        if (fileLen != rightFileLen) {
            throw new Exception();
        }
        byte[] coeff = new byte[nK];
        byte[] b = new byte[1];
        try {
            FileInputStream stream = new FileInputStream(nc_file);
            stream.read(b, 0, 1);
            //读入一行系数矩阵
            stream.read(coeff, 0, nK);
            stream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        //做首字节校验
        int k = (int) b[0];
        if (k != nK) {
            throw new Exception();
        }
        //做秩的校验
        int Row = coeffMatrix.length + 1;
        int[][] newCoefMatrix = new int[Row][nK];
        for (int i = 0; i < coeffMatrix.length; ++i) {
            for (int j = 0; j < nK; ++j) {
                newCoefMatrix[i][j] = coeffMatrix[i][j];
            }
        }
        //存入新加入的一行
        for (int i = 0; i < nK; ++i) {
            int element = 0;
            //正数化
            if (coeff[i] < 0) {
                element = IntAndBytes.negByte2int(coeff[i]);
            } else {
                element = coeff[i];
            }
            newCoefMatrix[Row - 1][i] = element;
        }
        byte[][] bt_newCoeff = IntAndBytes.intArray2byteArray(newCoefMatrix, Row, nK);
        NCUtil.nc_acquire();
        int rank1 = NCUtil.getRank(bt_newCoeff, Row, nK);
        NCUtil.nc_release();

        if (rank1 != Row) {
            throw new Exception();
        }
        //更新系数矩阵
        ++currentFileNum;
        coeffMatrix = newCoefMatrix;
    }

    //检查文件长度是否合法 编码系数是否有重复
    public ArrayList<File> checkFileLen() {
        ArrayList<File> fileList = MyFileUtils.getList_1_files(encodeFilePath);
        int fileNum = fileList.size();
        //检查下系数有多少行系数
        if (currentFileNum != fileNum) {
            currentFileNum = fileNum;
        }

        //说明文件数目和系数矩阵的行数是否一致
//        if(coeffMatrix.length!=fileNum){
//            //不一致   就说明出现了系数矩阵和文件出现了严重的数据同步错误
//        }
        return fileList;
    }

    //对文件进行编码，这里其实只是用单位矩阵进行封装
    public void encodeFile(File pfile) {
        int fileLen = (int) pfile.length();
        int perLen = fileLen / nK + (fileLen % nK != 0 ? 1 : 0);  //每个子代编码片长度
        //设置文件校对长度
        // rightFileLen = 1 + nK + perLen;
        InputStream inputStream;
        try {
            inputStream = new FileInputStream(pfile);
        } catch (FileNotFoundException e) {
            //读文件出错
            e.printStackTrace();
            return;
        }
        //对数据封装 K+单位矩阵+数据
        for (int m = 0; m < nK; ++m) {
            byte[] b = new byte[1 + nK];
            b[0] = (byte) nK;
            b[m + 1] = 1;
            // String fileName = (i + 1) + "_" + (m + 1) + ".nc";
            String fileName = pieceNo + "." + LocalInfor.getCurrentTime("MMddHHmmssSSS") + ".nc";
            File piece_file = MyFileUtils.creatFile(encodeFilePath, fileName);
            try {
                FileOutputStream fos = new FileOutputStream(piece_file);
                fos.write(b);    //写入文件
                fos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            MyFileUtils.splitFile(inputStream, encodeFilePath, fileName, perLen);
        }
        //初始化变量
        currentFileNum = nK;   //代表拥有所有的文件
        //系数矩阵
        coeffMatrix = new int[nK][nK];
        for (int i = 0; i < nK; ++i) {
            coeffMatrix[i][i] = 1;
        }
    }

    //对文件进行再编码   需要访问jni
    public File re_encodeFile() {
        //从编码文件路径中取出文件
        List<File> fileList = checkFileLen();
        if (fileList == null) {
            //检查长度时出错
            return null;
        }
        int fileNum = fileList.size();

        //如果只有一个编码文件的话，那就不用再编码
        if (fileNum == 1) {
            File file = fileList.get(0);
            //MyFileUtils.copyFile(file, re_encodeFilePath);
            return file;
        }
        //注意：用于再编码的文件长度必定都是一样的
        int fileLen = (int) (fileList.get(0).length());
        //用于存文件数组
        byte[][] fileData = new byte[fileNum][fileLen];
        for (int i = 0; i < fileNum; ++i) {
            File file = fileList.get(i);
            try {
                InputStream in = new FileInputStream(file);
                //b = new byte[fileLen];
                in.read(fileData[i]);    //读取文件中的内容到b[]数组
                in.close();
            } catch (IOException e) {
                //Toast.makeText(this, "读取文件异常", Toast.LENGTH_SHORT).show();
                e.printStackTrace();
                return null;
            }
        }
        NCUtil.nc_acquire();
        //存再编码结果
        byte[][] reEncodeData = new byte[1][fileLen];
        //随机编码
        reEncodeData = NCUtil.Reencode(fileData, fileNum, fileLen, 1);
        NCUtil.nc_release();
        //删除之前的再编码文件
        MyFileUtils.deleteAllFile(re_encodeFilePath, false);
        String fileName = pieceNo + "." + LocalInfor.getCurrentTime("MMddHHmmssSSS") + ".nc"; //pieceNo.time.re  //格式
        File re_encodeFile = MyFileUtils.writeToFile(re_encodeFilePath, fileName, reEncodeData[0]);
        return re_encodeFile;
    }

    //对文件进行解码     需要访问jni
    public boolean decode_pfile() {
        List<File> fileList = checkFileLen();
        if (fileList == null) {
            //检查长度时出错
            return false;
        }
        int fileNum = fileList.size();
        if (fileNum < nK) {
            //文件数目不足，无法解码
            return false;
        }
        int fileLen = (int) fileList.get(0).length();
        //用于存文件数组
        byte[][] fileData = new byte[nK][fileLen];   //如果文件很多，也只需nK个文件

        for (int i = 0; i < nK; ++i) {
            File file = fileList.get(i);
            try {
                InputStream in = new FileInputStream(file);
                //b = new byte[fileLen];
                in.read(fileData[i]);    //读取文件中的内容到b[]数组
                in.close();
            } catch (IOException e) {
                //Toast.makeText(this, "读取文件异常", Toast.LENGTH_SHORT).show();
                e.printStackTrace();
                return false;
            }
        }

        NCUtil.nc_acquire();
        //存解码结果
        int col = fileLen - 1 - nK;
        byte[][] origin_data = NCUtil.Decode(fileData, nK, fileLen);
        //origin_data = NCUtil.Decode(fileData, nK, fileLen);
        NCUtil.nc_release();
        if (origin_data == null) {
            return false;
        }

        //二维转化为一维
        int origin_file_len = nK * col;
        byte[] originData = new byte[origin_file_len];
        int ii = 0;
        for (int i = 0; i < nK; ++i) {
            for (int j = 0; j < col; ++j) {
                originData[ii] = origin_data[i][j];
                ++ii;
            }
        }
        //写入文件
        MyFileUtils.writeToFile(pieceFilePath, pieceNo + ".decode", originData);
        return true;
    }

    //检查有没有恢复出来的原文件片
    public File getOriginPFile() {
        String filePath = pieceFilePath + File.separator + pieceNo + ".decode";
        File file = new File(filePath);
        if (file.exists()) {
            return file;
        }
        //等待解码
        if (decode_pfile()) {
            File file1 = new File(filePath);
            return file1;
        } else {
            return null;
        }
    }


    /**
     * 以下是自动生成的Getter和Setter方法
     */
    public int getCurrentFileNum() {
        return currentFileNum;
    }

    public void setCurrentFileNum(int currentFileNum) {
        this.currentFileNum = currentFileNum;
    }

    public String getEncodeFilePath() {
        return encodeFilePath;
    }

    public void setEncodeFilePath(String encodeFilePath) {
        this.encodeFilePath = encodeFilePath;
    }

    public int getnK() {
        return nK;
    }

    public void setnK(int nK) {
        this.nK = nK;
    }

    public String getPieceFilePath() {
        return pieceFilePath;
    }

    public void setPieceFilePath(String pieceFilePath) {
        this.pieceFilePath = pieceFilePath;
    }

    public int getPieceNo() {
        return pieceNo;
    }

    public void setPieceNo(int pieceNo) {
        this.pieceNo = pieceNo;
    }

    public String getRe_encodeFilePath() {
        return re_encodeFilePath;
    }

    public void setRe_encodeFilePath(String re_encodeFilePath) {
        this.re_encodeFilePath = re_encodeFilePath;
    }

    public int getRightFileLen() {
        return rightFileLen;
    }

    public void setRightFileLen(int rightFileLen) {
        this.rightFileLen = rightFileLen;
    }

    public int[][] getCoeffMatrix() {
        return coeffMatrix;
    }

    public void setCoeffMatrix(int[][] coeffMatrix) {
        this.coeffMatrix = coeffMatrix;
    }

    public boolean isFileChanged() {
        return fileChanged;
    }

    public void setFileChanged(boolean fileChanged) {
        this.fileChanged = fileChanged;
    }
}
