package test;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Random;


public class NonAtomicIncDec extends Thread {

    // for random enque deque parts
    Random rand = new Random();
    // the number of threads
    static final int NumThreads = 2;
    static final int ITERS = 100;
    // only var
    static int x = 0;
    // the atomicity lock
    public static ReentrantLock re = new ReentrantLock();
    // ++ operation
    public void inc() {
        ++x;            
        // System.out.println("I " + x);
    }
    // -- operation
    public void dec(){
        --x;
        // System.out.println("D " + x);
    }

    //deciding the operation
    @Override
    public void run() {

        for(int i=0; i<ITERS; ++i){
            int operatn = i%2;
            if(operatn == 0)
                this.inc();
            else if(operatn == 1)
                this.dec();
        }

        return;
    }

    // the main function
    public static void main(String args[]) throws Exception {

        NonAtomicIncDec objects[] = new NonAtomicIncDec[NumThreads];
        for (int i = 0; i < NumThreads; i++) {
            objects[i] = new NonAtomicIncDec();
            objects[i].start();
        }
        for(int i=0; i < NumThreads; ++i){
            try{
                objects[i].join();
            }
            catch(Exception e){System.out.println(e);}
        }
    }
}
