package name.chakimar.vpnconnection;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.vpn.IVpnService;
import android.net.vpn.VpnManager;
import android.net.vpn.VpnProfile;
import android.net.vpn.VpnState;
import android.net.vpn.VpnType;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {

	private static final String TAG = "MainActivity";
    private static final String HOMEPAGE = "http://www.google.co.jp";
    private VpnProfile mProfile;
	private VpnManager mVpnManager;

    private ConnectivityReceiver mConnectivityReceiver =
            new ConnectivityReceiver();
	private WebView webview;
	
	/** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_PROGRESS);
        initWebView();
        
		mVpnManager = new VpnManager(this);
        mVpnManager.registerConnectivityReceiver(mConnectivityReceiver);

		//PPTP以外は未検証
		mProfile = createVpnProfile("PPTP");
        mProfile.setServerName("サーバー名を入力");
        mProfile.setName("名前を入力");
        //VPNに接続
        connect(mProfile, "ユーザー名を入力", "パスワードを入力");
        setContentView(webview);
        
    }

	private void initWebView() {
		webview = new WebView(this);
        webview.setWebViewClient(new WebViewClient());
        webview.setWebChromeClient(new MyWebChromeClient());
        webview.getSettings().setJavaScriptEnabled(true);
	}
    
    @Override
	protected void onDestroy() {
		super.onDestroy();
        mVpnManager.unregisterConnectivityReceiver(mConnectivityReceiver);
	}

	private void showToast(String msg) {
    	Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
    
    /**
     * VPNに接続する
     * @see AuthenticationActor.connect
     */
    private void connect(final VpnProfile profile, final String username, final String password) {

        mVpnManager.startVpnService();
	    /*
	     * ServiceConnectionの実装クラスを生成
	     */
		ServiceConnection c = new ServiceConnection() {
			public void onServiceConnected(ComponentName className,
                    IBinder service) {
                try {
                	//VPN接続を行う。ここで返ってくるbooleanはVPN接続完了を示したものではない。
                    boolean success = IVpnService.Stub.asInterface(service)
                            .connect(profile, username, password);
                    if (!success) {
                        Log.d(TAG, "~~~~~~ connect() failed!");
                    } else {
                        Log.d(TAG, "~~~~~~ connect() succeeded!");
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "connect()", e);
                    broadcastConnectivity(VpnState.IDLE,
                            VpnManager.VPN_ERROR_CONNECTION_FAILED);
                } finally {
                    MainActivity.this.unbindService(this);
                }
            }

            public void onServiceDisconnected(ComponentName className) {
                checkStatus();
            }
        };
        if (!bindService(c)) {
            broadcastConnectivity(VpnState.IDLE,
                    VpnManager.VPN_ERROR_CONNECTION_FAILED);
        }
    	
    }
    private boolean bindService(ServiceConnection c) {
        return mVpnManager.bindVpnService(c);
    }
    private void broadcastConnectivity(VpnState s, int errorCode) {
        mVpnManager.broadcastConnectivity(mProfile.getName(), s, errorCode);
    }

    private void broadcastConnectivity(VpnState s) {
        mVpnManager.broadcastConnectivity(mProfile.getName(), s);
    }

    private VpnProfile createVpnProfile(String type) {
        return mVpnManager.createVpnProfile(Enum.valueOf(VpnType.class, type));
    }
    
    /**
     * VPN接続状態を確認して、ブロードキャストを送る。
     * @see com.android.settings.vpn.VpnSettings.StatusChecker.check
     */
    public void checkStatus() {
    	//排他制御用のオブジェクト
        final ConditionVariable cv = new ConditionVariable();
        cv.close();
        ServiceConnection c = new ServiceConnection() {
            public synchronized void onServiceConnected(ComponentName className,
                    IBinder service) {
                cv.open();
                try {
                    IVpnService.Stub.asInterface(service).checkStatus(mProfile);
                } catch (RemoteException e) {
                    Log.e(TAG, "checkStatus()", e);
                    broadcastConnectivity(VpnState.IDLE);
                } finally {
                    unbindService(this);
                }
            }

            public void onServiceDisconnected(ComponentName className) {
                cv.open();
                broadcastConnectivity(VpnState.IDLE);
                unbindService(this);
            }
        };
        if (bindService(c)) {
            // wait for a second, let status propagate
            if (!cv.block(1000)) broadcastConnectivity(VpnState.IDLE);
        }
    }

    /**
     * BACKキーで終了する際に、VPN接続を切断する。
     */
	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		switch (event.getAction()) {
		case KeyEvent.ACTION_DOWN:
			switch (event.getKeyCode()) {
			case KeyEvent.KEYCODE_BACK:
				disconnect();
				break;
			}
			break;
		}
		return super.dispatchKeyEvent(event);
	}

	/**
	 * VPN接続を切断する。
     * @see com.android.settings.vpn.AuthenticationActor.disconnect
	 */
    public void disconnect() {
        ServiceConnection c = new ServiceConnection() {
            public void onServiceConnected(ComponentName className,
                    IBinder service) {
                try {
                    IVpnService.Stub.asInterface(service).disconnect();
                } catch (RemoteException e) {
                    Log.e(TAG, "disconnect()", e);
                    checkStatus();
                } finally {
                    unbindService(this);
                }
            }

            public void onServiceDisconnected(ComponentName className) {
                checkStatus();
            }
        };
        if (!bindService(c)) {
            checkStatus();
        }
    }

    /**
     * VPN接続状態変更イベントを取得。VPN接続完了時にホームページにアクセスする。
     */
    private class ConnectivityReceiver extends BroadcastReceiver {

		@Override
        public void onReceive(Context context, Intent intent) {
            String profileName = intent.getStringExtra(
                    VpnManager.BROADCAST_PROFILE_NAME);
            if (profileName == null) return;

            VpnState s = (VpnState) intent.getSerializableExtra(
                    VpnManager.BROADCAST_CONNECTION_STATE);

            if (s == null) {
                Log.e(TAG, "received null connectivity state");
                return;
            }

            showToast(s.toString());
            //VPN接続完了時にURL読み込み
            if (s == VpnState.CONNECTED) {
                new Handler().post(new Runnable() {
					@Override
					public void run() {
						webview.loadUrl(HOMEPAGE);
					}
				});
            }
        }
    }
    
    private class MyWebChromeClient extends WebChromeClient {

		@Override
		public void onReceivedTitle(WebView view, String title) {
			super.onReceivedTitle(view, title);
			setTitle(title);
		}
		
		@Override
		public void onProgressChanged(WebView view, int newProgress) {
			super.onProgressChanged(view, newProgress);
			setProgress(newProgress * 100);
		}
    }
}