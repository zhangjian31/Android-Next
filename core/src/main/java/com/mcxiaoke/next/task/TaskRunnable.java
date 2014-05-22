package com.mcxiaoke.next.task;

import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import com.mcxiaoke.next.utils.LogUtils;

import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * User: mcxiaoke
 * Date: 14-5-14
 * Time: 17:12
 */
class TaskRunnable<Result, Caller> implements Runnable {

    public static final String TAG = TaskRunnable.class.getSimpleName();
    public static final String SEPARATOR = "::";

    private Handler mHandler;
    private TaskCallable<Result> mCallable;
    private TaskCallback<Result> mCallback;
    private Future<?> mFuture;
    private WeakReference<Caller> mWeakCaller;

    private Result mResult;
    private Throwable mThrowable;

    private int mHashCode;
    private String mTag;

    private boolean mSerial;
    private boolean mCancelled;
    private boolean mDebug;

    public TaskRunnable(final Handler handler, final boolean serial,
                        final TaskCallable<Result> callable,
                        final TaskCallback<Result> callback,
                        final Caller caller) {
        mHandler = handler;
        mSerial = serial;
        mCallable = callable;
        mCallback = callback;
        mWeakCaller = new WeakReference<Caller>(caller);
        mHashCode = System.identityHashCode(caller);
        mTag = buildTag(caller);
        if (mDebug) {
            LogUtils.v(TAG, "TaskRunnable() hashCode=" + mHashCode
                    + " tag=" + mTag + " serial=" + serial);
        }
    }

    public void setDebug(boolean debug) {
        mDebug = debug;
    }


    // 重置所有字段
    private void reset() {
        if (mDebug) {
            LogUtils.v(TAG, "reset()");
        }
        mHandler = null;
        mCallback = null;
        mCallable = null;
        mFuture = null;
        mWeakCaller = null;
        mResult = null;
        mThrowable = null;
//            mHashCode=0;
//            mTag=null;
    }

    /**
     * 由于各种原因导致任务被取消
     * 原因：手动取消，线程中断，调用者不存在，回调接口不存在
     *
     * @return cancelled
     */
    private boolean isTaskCancelled() {
        if (mDebug) {
            final boolean cancelled = isCancelled();
            final boolean interrupted = isInterrupted();
            final boolean noCaller = mWeakCaller.get() == null;
            final boolean noCallback = mCallback == null;
            LogUtils.v(TAG, "isTaskCancelled() cancelled=" + cancelled
                    + " interrupted=" + interrupted);
            LogUtils.v(TAG, "isTaskCancelled() noCaller=" + noCaller
                    + " noCallback=" + noCallback);
        }
        return isCancelled() || isInterrupted()
                || mWeakCaller.get() == null || mCallback == null;
    }

    @Override
    public void run() {
        if (mDebug) {
            LogUtils.v(TAG, "run() start seq=" + getSequence()
                    + " thread=" + Thread.currentThread());
        }
        final Callable<Result> callable = mCallable;
        Result result = null;
        Throwable throwable = null;


        // check  task cancelled before execute
        boolean taskCancelled = isTaskCancelled();

        if (!taskCancelled) {
            try {
                result = callable.call();
            } catch (Throwable e) {
                throwable = e;
            }
        } else {
            if (mDebug) {
                LogUtils.v(TAG, "run() task is cancelled, ignore callable execute, seq="
                        + getSequence() + " thread=" + Thread.currentThread());
            }
        }

        // check task cancelled after task execute
        if (!taskCancelled) {
            taskCancelled = isTaskCancelled();
        }

        mResult = result;
        mThrowable = throwable;

        if (mDebug) {
            LogUtils.v(TAG, "run() end taskCancelled=" + taskCancelled
                    + " seq=" + getSequence() + " thread=" + Thread.currentThread());
        }

        onDone();

        // if not cancelled, notify callback
        if (!taskCancelled) {
            if (throwable != null) {
                onFailure(throwable);
            } else {
                onSuccess(result);
            }
        }

        onFinally();
    }

    public boolean cancel() {
        if (mDebug) {
            LogUtils.v(TAG, "cancel()");
        }
        mCancelled = true;
        boolean result = false;
        if (mFuture != null) {
            result = mFuture.cancel(true);
        }
        return result;
    }

    public Future<?> getFuture() {
        return mFuture;
    }

    public Result getResult() {
        return mResult;
    }

    public Throwable getThrowable() {
        return mThrowable;
    }

    public int getHashCode() {
        return mHashCode;
    }

    public String getTag() {
        return mTag;
    }

    public void setFuture(Future<?> future) {
        mFuture = future;
    }

    public boolean isActive() {
        return !isInactive();
    }

    private boolean isInactive() {
        return mFuture == null ||
                mFuture.isCancelled() ||
                mFuture.isDone();
    }

    public boolean isSerial() {
        return mSerial;
    }

    public boolean isCancelled() {
        return mCancelled;
    }

    public boolean isInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    /**
     * 回调，任务执行成功
     * 注意：回调函数在UI线程运行
     *
     * @param result   任务执行结果
     * @param callback 任务回调接口
     * @param <Result> 类型参数，任务结果类型
     */
    private void onSuccess(final Result result) {
        if (mDebug) {
            LogUtils.v(TAG, "onSuccess()");
        }
        final TaskCallable<Result> callable = mCallable;
        final TaskCallback<Result> callback = mCallback;
        postRunnable(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onTaskSuccess(result, callable.mMessage);
                }
            }
        });
    }

    /**
     * 回调，任务执行失败
     * 注意：回调函数在UI线程运行
     *
     * @param exception 失败原因，异常
     * @param callback  任务回调接口
     * @param <Result>  类型参数，任务结果类型
     */
    private void onFailure(final Throwable exception) {
        if (mDebug) {
            LogUtils.e(TAG, "onFailure() exception=" + exception);
        }
        final TaskCallable<Result> callable = mCallable;
        final TaskCallback<Result> callback = mCallback;
        postRunnable(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onTaskFailure(exception, callable.mMessage);
                }
            }
        });
    }

    private void onDone() {
        if (mDebug) {
            LogUtils.v(TAG, "onDone()");
        }
        final Handler handler = mHandler;
        final String tag = mTag;
        if (handler != null) {
            Message message = handler.obtainMessage(TaskExecutor.MSG_TASK_DONE, tag);
            handler.sendMessage(message);
        }
    }

    private void onFinally() {
        if (mDebug) {
            LogUtils.v(TAG, "onFinally()");
        }
        reset();
    }

    private void postRunnable(final Runnable runnable) {
        if (mHandler != null) {
            mHandler.post(runnable);
        }
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("NextRunnable{");
        builder.append("mResult=").append(mResult);
        builder.append(", mThrowable=").append(mThrowable);
        builder.append(", mHashCode=").append(mHashCode);
        builder.append(", mTag='").append(mTag).append('\'');
        builder.append(", mSerial=").append(mSerial);
        builder.append(", mCancelled=").append(mCancelled);
        builder.append(", mDebug=").append(mDebug);
        builder.append(", mCallback=").append(mCallback);
        builder.append('}');
        return builder.toString();
    }


    private static volatile int mSequence = 0;

    static int getSequence() {
        return mSequence;
    }

    static int incSequence() {
        return ++mSequence;
    }

    /**
     * 根据Caller生成对应的TAG，hashcode+类名+timestamp+seq
     *
     * @param caller 调用对象
     * @return 任务的TAG
     */
    private String buildTag(final Caller caller) {
        // caller的key是hashcode
        // tag的组成:className+hashcode+timestamp+seq
        final int hashCode = System.identityHashCode(caller);
        final String className = caller.getClass().getSimpleName();
        final int sequenceNumber = incSequence();
        final long timestamp = SystemClock.elapsedRealtime();

        if (mDebug) {
            LogUtils.v(TAG, "buildTag() class=" + className + " seq=" + sequenceNumber);
        }

        StringBuilder builder = new StringBuilder();
        builder.append(className).append(SEPARATOR);
        builder.append(hashCode).append(SEPARATOR);
        builder.append(timestamp).append(SEPARATOR);
        builder.append(sequenceNumber);
        return builder.toString();
    }
}