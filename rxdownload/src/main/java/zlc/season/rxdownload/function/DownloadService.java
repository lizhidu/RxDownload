package zlc.season.rxdownload.function;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import rx.subjects.BehaviorSubject;
import rx.subjects.SerializedSubject;
import rx.subjects.Subject;
import zlc.season.rxdownload.db.DataBaseHelper;
import zlc.season.rxdownload.entity.DownloadEvent;
import zlc.season.rxdownload.entity.DownloadEventFactory;
import zlc.season.rxdownload.entity.DownloadFlag;
import zlc.season.rxdownload.entity.DownloadMission;
import zlc.season.rxdownload.entity.DownloadRecord;


/**
 * Author: Season(ssseasonnn@gmail.com)
 * Date: 2016/11/10
 * Time: 09:49
 * FIXME
 */
public class DownloadService extends Service {
    public static final String INTENT_KEY = "zlc_season_rxdownload_max_download_number";
    private static final String TAG = "DownloadService";
    private DownloadBinder mBinder;
    private DataBaseHelper mDataBaseHelper;

    private volatile Map<String, Subject<DownloadEvent, DownloadEvent>> mSubjectPool;

    private Map<String, DownloadMission> mNowDownloading;
    private Queue<DownloadMission> mWaitingForDownload;
    private Map<String, DownloadMission> mWaitingForDownloadLookUpMap;

    private int MAX_DOWNLOAD_NUMBER = 5;
    private volatile AtomicInteger mCount = new AtomicInteger(0);

    private Thread mDownloadQueueThread;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Create Download Service...");
        mBinder = new DownloadBinder();

        mSubjectPool = new ConcurrentHashMap<>();
        mWaitingForDownload = new LinkedList<>();
        mWaitingForDownloadLookUpMap = new HashMap<>();
        mNowDownloading = new HashMap<>();

        mDataBaseHelper = DataBaseHelper.getSingleton(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Start Download Service...");
        //TODO: read download record from database
        if (intent != null) {
            MAX_DOWNLOAD_NUMBER = intent.getIntExtra(INTENT_KEY, 5);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Destroy Download Service...");
        mDownloadQueueThread.interrupt();
        for (String each : mNowDownloading.keySet()) {
            pauseDownload(each);
        }
        mDataBaseHelper.closeDataBase();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Bind Download Service...");
        mDownloadQueueThread = new Thread(new DownloadTaskDispatchRunnable());
        mDownloadQueueThread.start();
        return mBinder;
    }

    public Subject<DownloadEvent, DownloadEvent> getSubject(String url) {
        Log.d(TAG, url);
        Subject<DownloadEvent, DownloadEvent> subject = subject(url);
        Log.d(TAG, "subject:" + subject);
        if (!mDataBaseHelper.recordNotExists(url)) {
            DownloadRecord record = mDataBaseHelper.readSingleRecord(url);
            subject.onNext(DownloadEventFactory.getSingleton().factory(url, record.getFlag(), record.getStatus()));
        }
        return subject;
    }

    public Subject<DownloadEvent, DownloadEvent> subject(String url) {
        if (mSubjectPool.get(url) == null) {
            DownloadEvent defaultEvent = DownloadEventFactory.getSingleton().factory(url, DownloadFlag.NORMAL, null);
            Subject<DownloadEvent, DownloadEvent> subject = new SerializedSubject<>(BehaviorSubject.create
                    (defaultEvent));
            mSubjectPool.put(url, subject);
        }
        return mSubjectPool.get(url);
    }

    public boolean addDownloadMission(DownloadMission mission) {
        String url = mission.getUrl();
        if (mWaitingForDownloadLookUpMap.get(url) != null || mNowDownloading.get(url) != null) {
            return false;
        } else {
            if (mDataBaseHelper.recordNotExists(url)) {
                mDataBaseHelper.insertRecord(mission);
            } else {
                mDataBaseHelper.updateRecord(url, DownloadFlag.WAITING);
            }
            mWaitingForDownload.offer(mission);
            mWaitingForDownloadLookUpMap.put(url, mission);
            DownloadEvent event = DownloadEventFactory.getSingleton().factory(url, DownloadFlag.WAITING,
                    mDataBaseHelper.readStatus(url));
            subject(url).onNext(event);
            return true;
        }
    }

    public void pauseDownload(String url) {
        suspendDownloadAndSendEvent(url, DownloadFlag.PAUSED);
        mDataBaseHelper.updateRecord(url, DownloadFlag.PAUSED);
    }

    public void cancelDownload(String url) {
        suspendDownloadAndSendEvent(url, DownloadFlag.CANCELED);
        mDataBaseHelper.updateRecord(url, DownloadFlag.CANCELED);
    }

    public void deleteDownload(String url) {
        suspendDownloadAndSendEvent(url, DownloadFlag.DELETED);
        mDataBaseHelper.deleteRecord(url);
        mSubjectPool.remove(url);
    }

    private void suspendDownloadAndSendEvent(String url, int flag) {
        if (mWaitingForDownloadLookUpMap.get(url) != null) {
            mWaitingForDownloadLookUpMap.get(url).canceled = true;
        }
        if (mNowDownloading.get(url) != null) {
            Utils.unSubscribe(mNowDownloading.get(url).getSubscription());
            subject(url).onNext(DownloadEventFactory.getSingleton().factory(url, flag,
                    mNowDownloading.get(url).getStatus()));
            mCount.decrementAndGet();
            mNowDownloading.remove(url);
        } else {
            subject(url).onNext(DownloadEventFactory.getSingleton().factory(url, flag,
                    mDataBaseHelper.readStatus(url)));
        }
    }

    private class DownloadTaskDispatchRunnable implements Runnable {

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                DownloadMission mission = mWaitingForDownload.peek();
                if (null != mission) {
                    String url = mission.getUrl();
                    if (mission.canceled) {
                        mWaitingForDownload.remove();
                        mWaitingForDownloadLookUpMap.remove(url);
                        continue;
                    }
                    if (mNowDownloading.get(url) != null) {
                        mWaitingForDownload.remove();
                        mWaitingForDownloadLookUpMap.remove(url);
                        continue;
                    }
                    if (mCount.get() < MAX_DOWNLOAD_NUMBER) {
                        mission.start(mNowDownloading, subject(url), mCount, mDataBaseHelper);
                        mWaitingForDownload.remove();
                        mWaitingForDownloadLookUpMap.remove(url);
                    }
                }
            }
        }
    }

    public class DownloadBinder extends Binder {
        public DownloadService getService() {
            return DownloadService.this;
        }
    }
}
