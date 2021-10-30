
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

//Inspired by:
//https://medium.com/adamedelwiess/operating-system-6-thread-part-2-reader-writer-problem-spurious-wakeups-and-deadlocks-6e28ab161002
public class ReaderWriter extends Thread {
    SharedVariable first;   //a lock for this shared variable will be acquired first.
    SharedVariable second;  //a lock for this shared variable will be acquired second.

    boolean reader = true; //if true, this thread is a reader (changed in constructor)
    int operationCount = 50;  //number of operations to do in total for each thread.
    int averageSleepDuration = 300; //number of milliseconds to sleep between each operation.
    String name = ""; //the name of this reader/writer thread.

    //for printing, a handy way to tell threads apart.
    public String toString() {
        return (reader ? "Reader-" : "Writer-") + name;
    }

    //creates a reader/writer thread.  with the given setup.
    public ReaderWriter(boolean read, SharedVariable first, SharedVariable second, String name) {
        reader = read;
        this.first = first;
        this.second = second;
        this.name = name;
    }
    //an issue of some kind seems to happen when locks are out of order for a reader and
    //a writer.  The issue is that one of the two threads will get stuck in a loop waiting
    //on the condition variable without being signalled.
    public static void main(String[] args) {
        //create two shared variables.
        SharedVariable var = new SharedVariable();
        SharedVariable var2 = new SharedVariable();
        //create four reader/writer threads.
        ReaderWriter reader1 = new ReaderWriter(true, var, var2, "1");
        ReaderWriter reader2 = new ReaderWriter(true, var2, var, "2");
        ReaderWriter writer1 = new ReaderWriter(false, var, var2, "1");
        ReaderWriter writer2 = new ReaderWriter(false, var2, var, "2");
        //start all the threads.
        reader2.start();
        writer1.start();
        reader1.start();
        writer2.start();
    }

    //what a reader/writer should do.
    @Override
    public void run() {
        for(int i = 0; i < operationCount; i++) {  //do some number of applications...
            //pausing some amount of time between them (to increase the likelihood of problems like deadlock).
            try { sleep((int)(2*averageSleepDuration*Math.random())); } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (reader) { //this is a reader thread.
                readLockAll();  //2 phase locking: first get all the locks.
                System.out.println("(" + first + ", " + second + ")"); //do the reading task.
                first.readUnlock(); //unlock the locks (order doesn't matter here)
                second.readUnlock();
            } else { //writer
                writeLockAll(); //2 phase locking: first get all the locks.
                first.increment(); //do the writing task.
                second.increment(); //do the writing task.
                first.writeUnlock(); //unlock both locks, order doesn't matter.
                second.writeUnlock();
                //System.out.println("wrote" + i);
            }
        }
        System.out.println("***" + this + " done!");
    }

    //locks both varialbes in read lock mode.
    //see writeLockAll() for internal detailed comments.
    private void readLockAll() {
        boolean done = false;
        double delay = 1;
        System.out.println(this + " locking all");
        while(!done) {
            if(! first.readLock()) continue;
            System.out.println(this + " acquired " + first);
            if(!second.readLock()) {
                first.readUnlock();
                try {
                    sleep((long) delay);
                    delay *= 1 + (Math.random() * 3);
                } catch (InterruptedException ie) {

                }
            } else {
                System.out.println(this + " acquired " + second);
                done = true;
            }
        }
    }

    //gets a write-lock on both variables.
    private void writeLockAll() {
        boolean done = false;  //true if the loop should exit.
        double delay = 1;  //the initial delay (don't believe this is necessary).
        System.out.println(this + " locking all");
        while(!done) {  //while both locks aren't successfully write-acquired...
            if(! first.writeLock()) continue;  //if the first lock can't be acquired, restart the loop.
            System.out.println(this + " acquired " + first);
            if(!second.writeLock()) { //first lock acquired, try getting the second.
                first.writeUnlock();  //failed to get the second lock, so unlock the first one.
                try {
                    sleep((long) delay);  //pause for some amount of time. (unnecessary?)
                    delay *= 1 + (Math.random() * 3); //increase pause time for next round (unnecessary?)
                } catch (InterruptedException ie) {  //should never happen in this example.

                }
            } else {
                //both locks acquired!
                System.out.println(this + " acquired " + second);
                done = true; //indicate to exit the loop.
            }
        }
    }
}

//including locking mechanism as part of the variable class.
//Probably a bad design, but was easier to work with.
class SharedVariable {
    static long waitTime = 1000000L; //one millisecond in nanos: how long to wait for a condition (see wait below)
    private int[] array = new int[20];  //a variable that will be changed.
    static int idSource = 0;  //a way to id each variable.
    ReentrantLock mutex = new ReentrantLock(true);  //need a mutex.
    // It is reentrant, but that should never have an effect.
    //need one condition each for readers and for writers.
    Condition read_phase = mutex.newCondition(), write_phase = mutex.newCondition();
    int readers = 0;  //need to keep track of the number of readers (-1 indicates writing).
    int id = idSource++; //set the id of a variable.

    //increments every value of the array.
    //an array is needed because, integer operations are atomic in java,
    //incrementing an entire array, on the other hand, is very much not atomic.
    public void increment() {
        for(int i = 0; i < array.length; i++) {
            array[i]++;
        }
    }

    //returns some info about this variable.
    //<id>: [<value>, <sameness>, <readers>]
    //where id is the name of this variable.
    //      value is the current average value in the array.
    //      sameness is true if all values of the array are the same.
    //      readers the number of readers which have read-lock on this variable.
    //sameness should ALWAYS be true.
    public String toString() {
        double sum = 0;
        boolean same = true;
        Integer first = null;
        for(int i : array) {
            sum += i;
            if(first == null) first = i;
            if(i != first) same = false;
        }
        sum /= array.length;
        return id + ": [" + (int)sum + ", " + same + ", " + readers + "]";
    }

    //tries to acquire a readlock on this variable.
    //returns true if the call is successful.
    //see writelock code for internal detailed comments.
    public boolean readLock() {
        mutex.lock();
        while (readers == -1) if(! wait(read_phase)) {
            mutex.unlock();
            return false;
        }
        readers++;
        mutex.unlock();
        return true;
    }

    //relinquishes a readlock on this variable.
    public void readUnlock() {
        mutex.lock();
        readers--;
        if (readers == 0) write_phase.signal();
        mutex.unlock();
    }

    //tries to acquire a writelock on this variable.
    //returns true if the call is successful.
    public boolean writeLock() {
        mutex.lock();  //first acquire the lock.
        while (readers != 0) //repeat until we acquire a write-lock.
            if(!wait(write_phase)) { //can't acquire a write-lock so wait a while.
                mutex.unlock();  //potential deadlock, this method failed.  unlock first.
                return false;  //return failure condition.
            }
        readers = -1;  //loop exited = success! set mode to writing.
        mutex.unlock();  //unlock the lock
        return true;  //return success condition.
    }

    //relinquishes a write-lock on this variable.
    public void writeUnlock() {
        mutex.lock();
        readers = 0;
        read_phase.signalAll();
        write_phase.signal();
        mutex.unlock();
    }

    //waits for some amount of time on a condition
    //returns true if the condition signaled the thread.
    //returns false if all the wait time is used up.
    private boolean wait(Condition cond) {
        try {
            System.out.println(Thread.currentThread() + " about to wait (variable " + this + ")");
            if(cond.awaitNanos((long)(Math.random() * waitTime)) < 0) {
                System.out.println("potential deadlock");
                return false;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new RuntimeException("Bad stuff happened");
        }
        return true;
    }
}

