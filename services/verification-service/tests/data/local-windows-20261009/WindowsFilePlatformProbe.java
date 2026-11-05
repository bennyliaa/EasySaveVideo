import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
public class WindowsFilePlatformProbe {
    public static void main(String[] args) throws Exception {
        Path p=Files.createTempFile("esv-platform-owned-", ".bin");
        try {
            BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            System.out.println("regular="+a.isRegularFile()+" symbolic="+a.isSymbolicLink()+" fileKeyPresent="+(a.fileKey()!=null));
            if(a.fileKey()!=null)System.out.println("fileKeyType="+a.fileKey().getClass().getName()+" fileKeyLength="+a.fileKey().toString().length());
            FileOutputStream output=new FileOutputStream(p.toFile());FileDescriptor fd=output.getFD();
            System.out.println("outputValidBefore="+fd.valid());output.close();System.out.println("outputValidAfter="+fd.valid());
            FileInputStream input=new FileInputStream(p.toFile());FileDescriptor read=input.getFD();
            input.close();System.out.println("inputValidAfter="+read.valid());
        } finally {Files.delete(p);}
    }
}
