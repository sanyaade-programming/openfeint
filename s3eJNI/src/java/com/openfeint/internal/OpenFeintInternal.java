package com.openfeint.internal;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import org.apache.commons.codec.binary.Hex;
import org.apache.http.impl.client.AbstractHttpClient;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParser;
import org.xmlpull.v1.XmlPullParser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;

import com.openfeint.api.Notification;
import com.openfeint.api.OpenFeintDelegate;
import com.openfeint.api.OpenFeintSettings;
import com.openfeint.api.R;
import com.openfeint.api.resource.CurrentUser;
import com.openfeint.api.resource.User;
import com.openfeint.internal.db.DB;
import com.openfeint.internal.notifications.SimpleNotification;
import com.openfeint.internal.notifications.TwoLineNotification;
import com.openfeint.internal.request.BaseRequest;
import com.openfeint.internal.request.BlobPostRequest;
import com.openfeint.internal.request.Client;
import com.openfeint.internal.request.GenericRequest;
import com.openfeint.internal.request.IRawRequestDelegate;
import com.openfeint.internal.request.JSONRequest;
import com.openfeint.internal.request.OrderedArgList;
import com.openfeint.internal.request.RawRequest;
import com.openfeint.internal.request.multipart.ByteArrayPartSource;
import com.openfeint.internal.request.multipart.FilePartSource;
import com.openfeint.internal.request.multipart.PartSource;
import com.openfeint.internal.resource.BlobUploadParameters;
import com.openfeint.internal.resource.ServerException;
import com.openfeint.internal.ui.IntroFlow;
import com.openfeint.internal.ui.WebViewCache;

public class OpenFeintInternal {

    // Class constants
	private static final String TAG = "OpenFeint";
	private static final boolean DEVELOPMENT_LOGGING_ENABLED = false;

    // Static members
    private static OpenFeintInternal sInstance;

    // Members that need serialization
	private CurrentUser mCurrentUser;
	Client mClient;
	private boolean mCurrentlyLoggingIn;
	private boolean mCreatingDeviceSession;
	private boolean mDeviceSessionCreated;
	private boolean mBanned;
	
	private boolean mApproved;
	private boolean mDeclined;

    // Members that don't need serialization
	
	// nb: we're basically using this as a flag to prevent us from deserializing from a bundle when we're already set up.
	// it sure would be nice if android let the Application serialize stuff to a bundle instead of just the Activities! 
	private boolean mDeserializedAlready; 
	Handler mMainThreadHandler;
	OpenFeintDelegate mDelegate;
	OpenFeintSettings mSettings;
	Analytics analytics;
	private SyncedStore mPrefs;
	private Runnable mPostDeviceSessionRunnable;
	private List<Runnable> mQueuedPostDeviceSessionRunnables; // These are requests to run after device session, so there can be many
	private Runnable mPostLoginRunnable; // This is what intent to launch after login, so there should only be one
	private List<Runnable> mQueuedPostLoginRunnables; // These are requests to run after login, so there can be many
	String mUDID;
	String mAppVersion;

	Properties mInternalProperties;
	String  mServerUrl;
	private Context mContext;
	
    private void _saveInstanceState(Bundle outState) {
		if (mCurrentUser != null) outState.putString("mCurrentUser", mCurrentUser.generate());
		if (mClient != null) mClient.saveInstanceState(outState);
	    outState.putBoolean("mCurrentlyLoggingIn", mCurrentlyLoggingIn);
	    outState.putBoolean("mCreatingDeviceSession", mCreatingDeviceSession);
	    outState.putBoolean("mDeviceSessionCreated", mDeviceSessionCreated);
	    outState.putBoolean("mBanned", mBanned);
	    outState.putBoolean("mApproved", mApproved);
	    outState.putBoolean("mDeclined", mDeclined);
	}
	
	private void _restoreInstanceState(Bundle inState) {
		if (!mDeserializedAlready && inState != null) {
			mCurrentUser = (CurrentUser)userFromString(inState.getString("mCurrentUser"));
			if (mClient != null) mClient.restoreInstanceState(inState);
			mCurrentlyLoggingIn = inState.getBoolean("mCurrentlyLoggingIn");
			mCreatingDeviceSession = inState.getBoolean("mCreatingDeviceSession");
			mDeviceSessionCreated = inState.getBoolean("mDeviceSessionCreated");
			mBanned = inState.getBoolean("mBanned");
			mApproved = inState.getBoolean("mApproved");
			mDeclined = inState.getBoolean("mDeclined");
			mDeserializedAlready = true;
		}
	}
	
	public static void saveInstanceState(Bundle outState) {
		getInstance()._saveInstanceState(outState);
	}
	
	public static void restoreInstanceState(Bundle inState) {
		getInstance()._restoreInstanceState(inState);
	}

	// Hey psuedo-singleton
	public static OpenFeintInternal getInstance() { return sInstance; }
	public OpenFeintDelegate getDelegate() { return mDelegate; }
	public AbstractHttpClient getClient() { return mClient; }
	public SyncedStore getPrefs() {
		if (mPrefs == null) {
			mPrefs = new SyncedStore(getContext());
		}
		return mPrefs;
	}
	
	private void saveUser(SyncedStore.Editor e, User u) {
		e.putString("last_logged_in_user", u.generate());
	}
	
	private void clearUser(SyncedStore.Editor e) {
		e.remove("last_logged_in_user");
	}

	private User loadUser() {
		String urep = null;
		SyncedStore.Reader r = getPrefs().read();
		try {
			urep = r.getString("last_logged_in_user", null);
		} finally {
			r.complete();
		}
		return userFromString(urep);
	}

	private static User userFromString(String urep) {
		if (urep == null) return null;

		try {
			JsonFactory jsonFactory = new JsonFactory(); // A throwaway factory.
			JsonParser jp = jsonFactory.createJsonParser(new ByteArrayInputStream(urep.getBytes()));
			JsonResourceParser jrp = new JsonResourceParser(jp);
			Object responseBody = jrp.parse();
			
			if (responseBody != null && responseBody instanceof User) {
				return (User)responseBody;				
			}
		} catch (IOException e) {
		}
		return null;
	}
	
	private void userLoggedIn(User loggedInUser) {
		mCurrentUser = new CurrentUser();
		mCurrentUser.shallowCopyAncestorType(loggedInUser);
		
		SyncedStore.Editor e = getPrefs().edit();
		try {
			e.putString("last_logged_in_server", getServerUrl());
			saveUserApproval(e);
			
			saveUser(e, loggedInUser);
		} finally {
			e.commit();
		}
		
		if (mDelegate != null) {
			mDelegate.userLoggedIn(mCurrentUser);
		}
		getAnalytics().markSessionOpen(true);
		AchievementUnlockCache.reset();
	}

	private void userLoggedOut() {
		User previousLocalUser = mCurrentUser;
		mCurrentUser = null;
		
		mDeviceSessionCreated = false;
		
		clearPrefs();
		
		if (mDelegate != null) {
			mDelegate.userLoggedOut(previousLocalUser);
		}
		getAnalytics().markSessionClose();
	}

	private void clearPrefs() {
		SyncedStore.Editor e = getPrefs().edit();
		try {
			e.remove("last_logged_in_server");
			e.remove("last_logged_in_user_name");
	
			clearUser(e);
		} finally {
			e.commit();
		}
	}

	public void createUser(final String userName, final String email, final String password, final String passwordConfirmation, final IRawRequestDelegate delegate) {
		OrderedArgList bootstrapArgs = new OrderedArgList();
		bootstrapArgs.put("platform", "android");
		bootstrapArgs.put("of-version", getOFVersion());
		bootstrapArgs.put("app-version", getAppVersion());
		bootstrapArgs.put("user[name]", userName);
		bootstrapArgs.put("user[http_basic_credential_attributes][email]", email);
		bootstrapArgs.put("user[http_basic_credential_attributes][password]", password);
		bootstrapArgs.put("user[http_basic_credential_attributes][password_confirmation]", passwordConfirmation);
		
		RawRequest userCreate = new RawRequest(bootstrapArgs) {
			@Override public String method() { return "POST"; }
			@Override public String path() { return "/xp/users.json"; }
			@Override public void onSuccess(Object responseBody) {
				userLoggedIn((User)responseBody);
			}
		};
		
		userCreate.setDelegate(delegate);		
		_makeRequest(userCreate);
	}

	public static String getModelString() {
		return "p(" + android.os.Build.PRODUCT + ")/m(" + android.os.Build.MODEL + ")";
	}

	public static String getOSVersionString() {
		return "v" + android.os.Build.VERSION.RELEASE + " (" + android.os.Build.VERSION.INCREMENTAL + ")";
	}

	public static String getScreenInfo() {
		DisplayMetrics metrics = Util.getDisplayMetrics();
		return String.format("%dx%d (%f dpi)", metrics.widthPixels, metrics.heightPixels, metrics.density);
	}
	
	public static String getProcessorInfo() {
		String family = "unknown";
		try {
			for (String l : cat("/proc/cpuinfo").split("\n")) {
				if (l.startsWith("Processor\t")) {
					family = l.split(":")[1].trim();
					break;
				}
			}
		} catch (Exception e) {
			// Johnny can't parse
		}

		return String.format("family(%s) min(%s) max(%s)",
				family,
				cat("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq").split("\n")[0],
				cat("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").split("\n")[0]);
	}
	
	private static String cat(String filename) {
		FileInputStream f;
		try {
			f = new FileInputStream(filename);
			BufferedReader br = new BufferedReader(new InputStreamReader(f), 8192);
			StringBuilder sb = new StringBuilder();
			String line = null;
			while ((line = br.readLine()) != null) {
				sb.append(line + "\n");
			}
			br.close();
			return sb.toString();
		} catch (Exception e) {
			// d'oh
		}

		return "unknown";
	}

	public Map<String, Object> getDeviceParams() {
    	HashMap<String,Object> device = new HashMap<String,Object>();
    	device.put("identifier",     		getUDID());
    	device.put("hardware", 				getModelString());
    	device.put("os",       				getOSVersionString());
    	device.put("screen_resolution",		getScreenInfo());
    	device.put("processor",       		getProcessorInfo());
    	return device;
	}
	
	public void createDeviceSession() {
		if (mCreatingDeviceSession || mDeviceSessionCreated) return; // not necessary
		
		HashMap<String,Object> argMap = new HashMap<String,Object>();
		argMap.put("platform", "android");
		argMap.put("device", getDeviceParams());
		OrderedArgList args = new OrderedArgList(argMap);

		mCreatingDeviceSession = true;
		
		// NB: this is signed, and NEEDS to be signed, so that the game session will be created
		// without being stepped on by concurrent requests.
		RawRequest deviceSession = new RawRequest(args) {
			@Override public String method() { return "POST"; }
			@Override public String path() { return "/xp/devices"; }
			@Override public boolean needsDeviceSession() { return false; }
			@Override public void onResponse(int responseCode, Object responseBody) {
				mCreatingDeviceSession = false;

				if (200 <= responseCode && responseCode < 300) {
					mDeviceSessionCreated = true;
					if (mPostDeviceSessionRunnable != null) {
						log(TAG, "Launching post-device-session runnable now.");
						mMainThreadHandler.post(mPostDeviceSessionRunnable);
					}
					
				} else {
					showOfflineNotification(responseCode, responseBody);
				}

				// the queued ones run regardless.
				if (mQueuedPostDeviceSessionRunnables != null) for (Runnable r : mQueuedPostDeviceSessionRunnables) {
					mMainThreadHandler.post(r);
				}

				mPostDeviceSessionRunnable = null;
				mQueuedPostDeviceSessionRunnables = null;
				mPostLoginRunnable = null;
			}
		};
		
		_makeRequest(deviceSession);
	}
	
    public final void runOnUiThread(Runnable action) {
    	mMainThreadHandler.post(action);
    }
    
	public void loginUser(final String userName, final String password, final String userID, final IRawRequestDelegate delegate) {
		if (checkBan()) return;

		if (mCreatingDeviceSession || !mDeviceSessionCreated) {
			if (!mCreatingDeviceSession) {
				createDeviceSession();
			}
			
			// just login when we're done.
			log(TAG, "No device session yet - queueing login.");

			mPostDeviceSessionRunnable = new Runnable() {
				@Override public void run() {
					loginUser(userName, password, userID, delegate);
				}
			};
			return;
		}

		boolean allowToast = true;
		
		OrderedArgList bootstrapArgs = new OrderedArgList();
		bootstrapArgs.put("platform", "android");
		if (userName != null && password != null) {
			bootstrapArgs.put("login", userName);
			bootstrapArgs.put("password", password);
			allowToast = false;
		}
		// For returning users we need to use the user's id instead of
		// login because when Emoji names are used they're sent wrongly 
		// to the server by the webview somehow.
		if (userID != null && password != null){
			bootstrapArgs.put("user_id", userID);
			bootstrapArgs.put("password", password);
			allowToast = false;
		}
		bootstrapArgs.put("of-version", getOFVersion());
		bootstrapArgs.put("app-version", getAppVersion());

		mCurrentlyLoggingIn = true;
		
		final boolean finalToast = allowToast;
		
		RawRequest userLogin = new RawRequest(bootstrapArgs) {
			@Override public String method() { return "POST"; }
			@Override public String path() { return "/xp/sessions.json"; }
			@Override public void onResponse(int responseCode, Object responseBody) {
				mCurrentlyLoggingIn = false;

				if (200 <= responseCode && responseCode < 300) {
					userLoggedIn((User)responseBody);

					// The singular one only runs if there was a successful login.
					if (mPostLoginRunnable != null) {
						log(TAG, "Launching post-login runnable now.");
						mMainThreadHandler.post(mPostLoginRunnable);
					}
					
				} else {
					if (finalToast) {
						showOfflineNotification(responseCode, responseBody);
					}
				}

				// the queued ones run regardless.
				if (mQueuedPostLoginRunnables != null) for (Runnable r : mQueuedPostLoginRunnables) {
					mMainThreadHandler.post(r);
				}

				mPostLoginRunnable = null;
				mQueuedPostLoginRunnables = null;
			}
		};
		
		userLogin.setDelegate(delegate);
		_makeRequest(userLogin);
	}
	
	public void submitIntent(final Intent intent) {
		// I know this seems a strange place to put it, but
		// this is the main user-activated entrypoint to OF.
		// If we get here, that means we are undeclining, if
		// we've actually declined (but don't erase the setting
		// yet - it'll get erased if they actually log in).
		mDeclined = false;
		
		final Runnable r = new Runnable() {
			@Override public void run() {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				getContext().startActivity(intent);
			}
		};

		if (!isUserLoggedIn()) {
			log(TAG, "Not logged in yet - queueing intent "+intent.toString()+" for now.");
			mPostLoginRunnable = r;
			if (!currentlyLoggingIn()) {
				login();
			}
		} else {
			mMainThreadHandler.post(r); // main thread plz
		}
	}

	public void logoutUser(final IRawRequestDelegate delegate) {
		OrderedArgList bootstrapArgs = new OrderedArgList();
		bootstrapArgs.put("platform", "android");
		
		RawRequest userLogout = new RawRequest(bootstrapArgs) {
			@Override public String method() { return "DELETE"; }
			@Override public String path() { return "/xp/sessions.json"; }
		};
		
		userLogout.setDelegate(delegate);
		_makeRequest(userLogout);
		
		// clear our local stuff immediately, don't wait for req to complete.
		userLoggedOut();
	}

	public static void genericRequest(final String path, final String method, final Map<String, Object> args, final Map<String, Object> httpParams, final IRawRequestDelegate delegate) {
		makeRequest(new GenericRequest(path, method, args, httpParams, delegate));
	}
	
	public void userApprovedFeint() {
		mApproved = true;
		mDeclined = false;
		SyncedStore.Editor e = getPrefs().edit();
		
		try {
			saveUserApproval(e);
		} finally {
			e.commit();
		}

		launchIntroFlow();
	}

	private void saveUserApproval(SyncedStore.Editor e) {
		e.remove(getContext().getPackageName() + ".of_declined");
	}
	
	public void userDeclinedFeint() {
		mApproved = false;
		mDeclined = true;
		
		SyncedStore.Editor e = getPrefs().edit();
		try {
			e.putString(getContext().getPackageName() + ".of_declined", "sadly");
		} finally {
			e.commit();
		}
	}

	public boolean currentlyLoggingIn() {
		return mCurrentlyLoggingIn || mCreatingDeviceSession;
	}

	public CurrentUser getCurrentUser() { return mCurrentUser; }
	
	public boolean isUserLoggedIn() { return getCurrentUser() != null; }
	
	public String getUDID() {
		if (mUDID == null) {
			mUDID = findUDID();
		}
		return mUDID;
	}

	public Properties getInternalProperties() { return mInternalProperties; }
	
	public String getServerUrl() {
	  if(mServerUrl == null){
	    // load and normalize url
	    String raw = getInternalProperties().getProperty("server-url").toLowerCase().trim(); 
	    if(raw.endsWith("/")) {
  	    mServerUrl =  raw.substring(0, raw.length() - 1);
  	  } else {
  	    mServerUrl = raw;
  	  }
	  }
	  
    return mServerUrl;
	}
	
	public String getOFVersion() { return getInternalProperties().getProperty("of-version"); }

	public String getAppName() { return mSettings.name; }
	public String getAppID() { return mSettings.id; }
	public Map<String, Object> getSettings() { return mSettings.settings; }
	public String getAppVersion() {
		if (mAppVersion == null) {
			Context c = getContext();
			try {
				PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
				mAppVersion = p.versionName;
			} catch (Exception e) {
				mAppVersion = "1.0";
			}
		}
		return mAppVersion;
	}

	public Context getContext() { return mContext; }
	
	public void displayErrorDialog(final CharSequence errorMessage) {
		mMainThreadHandler.post(new Runnable() {
			@Override public void run() {
				new AlertDialog.Builder(getContext())
				.setMessage(errorMessage)
				.setNegativeButton(OpenFeintInternal.getRString(R.string.of_ok), null)
				.show();
			}
		});
	}

	private String findUDID() {
		
		String androidID = android.provider.Settings.Secure.getString(getContext().getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
		
		// If there's no android ID, or if it's the magic universal 2.2 emulator ID, we need to generate one.
		if (androidID != null && !androidID.equals("9774d56d682e549c")) {
			return "android-id-" + androidID;
		} else {
			// We're in an emulator.
			SyncedStore.Reader r = getPrefs().read();
			try {
				androidID = r.getString("udid", null);
			} finally {
				r.complete();
			}
			
			if (androidID == null) {
				byte randomBytes[] = new byte[16];
				new Random().nextBytes(randomBytes);
				androidID = "android-emu-" + new String(Hex.encodeHex(randomBytes)).replace("\r\n", "");
				
				SyncedStore.Editor e = getPrefs().edit();
				try {
					e.putString("udid", androidID);
				} finally {
					e.commit();
				}
			}
			
			return androidID;
		}
	}

	public static void makeRequest(final BaseRequest req) {
		OpenFeintInternal ofi = getInstance();
		if (ofi == null) {
			ServerException e = new ServerException();
			e.exceptionClass = "NoFeint";
			e.message = "OpenFeint has not been initialized.";
			req.onResponse(0, e.generate().getBytes());
		} else {
			ofi._makeRequest(req);
		}
	}
	
	private final void _makeRequest(final BaseRequest req) {
		if (!isUserLoggedIn() && req.wantsLogin() && _lastLoggedInUser() != null && isFeintServerReachable()) {

			// make sure we're logging in.
			login();
			if (mQueuedPostLoginRunnables == null) {
				mQueuedPostLoginRunnables = new ArrayList<Runnable>();
			}
			mQueuedPostLoginRunnables.add(new Runnable() {
				@Override public void run() {
					mClient.makeRequest(req);
				}
			});
		} else if (!mDeviceSessionCreated && req.needsDeviceSession()) {
			// make sure it's in progress, at least.
			createDeviceSession();
			
			if (mQueuedPostDeviceSessionRunnables == null) {
				mQueuedPostDeviceSessionRunnables = new ArrayList<Runnable>();
			}
			mQueuedPostDeviceSessionRunnables.add(new Runnable() {
				@Override public void run() {
					mClient.makeRequest(req);
				}
			});
		} else {
			mClient.makeRequest(req);
		}
	}
	
	public void uploadFile(final String xpApiPath, final String filePath, final String contentType, final IRawRequestDelegate delegate) {
		try {
			String fileName = filePath;
			String[] parts = filePath.split("/");
			if (parts.length > 0) {
				fileName = parts[parts.length-1];
			}
			uploadFile(xpApiPath, new FilePartSource(fileName, new File(filePath)), contentType, delegate);
		} catch (FileNotFoundException e) {
			ServerException fakeServerException = new ServerException();
			fakeServerException.exceptionClass = "FileNotFound";
			fakeServerException.message = "Couldn't open the file '"+filePath+"'.";
			delegate.onResponse(0, fakeServerException.generate());
		}
	}

	public void uploadFile(final String xpApiPath, final String fileName, final byte[] fileData, final String contentType, final IRawRequestDelegate delegate) {
		uploadFile(xpApiPath, new ByteArrayPartSource(fileName, fileData), contentType, delegate);
	}

	public void uploadFile(final String xpApiPath, final PartSource partSource, final String contentType, final IRawRequestDelegate delegate) {
		JSONRequest xpRequest = new JSONRequest() {
			@Override public boolean wantsLogin() { return true; }
			@Override public String method() { return "POST"; }
			@Override public String path() { return xpApiPath; }
			@Override public void onSuccess(Object responseBody) {
				BlobPostRequest bp = new BlobPostRequest((BlobUploadParameters)responseBody, partSource, contentType);
				bp.setDelegate(delegate);
				_makeRequest(bp);
			}
		};
		_makeRequest(xpRequest);
	}
	
	public int getResource(String resourceName) {
		String packageName = getContext().getPackageName();
		return getContext().getResources().getIdentifier(resourceName, null, packageName);
	}

	public static String getRString(int id) {
		final OpenFeintInternal ofi = getInstance();
		final Context ctx = ofi.getContext();
		return ctx.getResources().getString(id);
	}

	public static void initializeWithoutLoggingIn(Context ctx, OpenFeintSettings settings, OpenFeintDelegate delegate) {
		// Check permissions and stuff.
		if (!validateManifest(ctx)) return;

		if (sInstance == null) {
			sInstance = new OpenFeintInternal(settings, ctx);
		} 
		
		sInstance.mDelegate = delegate;
		
		if (!sInstance.mDeclined) {
			sInstance.createDeviceSession();
		}
	}
	
	public static void initialize(Context ctx, OpenFeintSettings settings, OpenFeintDelegate delegate) {
		initializeWithoutLoggingIn(ctx, settings, delegate);
		final OpenFeintInternal ofi = getInstance();
		if (ofi != null) {
			ofi.login();
		}
	}
	
	public void setDelegate(OpenFeintDelegate delegate) {
		mDelegate = delegate;
	}
		
	private static boolean validateManifest(Context appContext) {		
		// Check ActivityInfo.
		final PackageManager packageManager = appContext.getPackageManager();
		try {
			final PackageInfo packageInfo = packageManager.getPackageInfo(appContext.getPackageName(), PackageManager.GET_ACTIVITIES | PackageManager.GET_PERMISSIONS);
			
			final String neededActivityInfo[] = new String[] {
				"com.openfeint.api.ui.Dashboard",
				"com.openfeint.internal.ui.IntroFlow",
				"com.openfeint.internal.ui.Settings",
				"com.openfeint.internal.ui.NativeBrowser",
			};
			
			for (String n : neededActivityInfo) {
				boolean victory = false;
				for (ActivityInfo ai : packageInfo.activities) {
					if (ai.name.equals(n)) {
						if (ai.configChanges != (ActivityInfo.CONFIG_ORIENTATION | ActivityInfo.CONFIG_KEYBOARD_HIDDEN))
						{
							Log.v(TAG, String.format("ActivityInfo for %s has the wrong configChanges.\nPlease consult README.txt for the correct configuration.", n));
							return false;
						}
						
						victory = true;
						break;
					}
				}
				if (!victory) {
					Log.v(TAG, String.format("Couldn't find ActivityInfo for %s.\nPlease consult README.txt for the correct configuration.", n));
					return false;
				}
			}

			// check permissions.
			final String neededPermissionInfo[] = new String[] {
					android.Manifest.permission.INTERNET,
			};
			
			for (String n : neededPermissionInfo) {
				boolean victory = false;
				for (String p : packageInfo.requestedPermissions) {
					if (p.equals(n)) {
						victory = true;
						break;
					}
				}
				
				if (!victory) {
					Log.v(TAG, String.format("Permission '%s' not requested in manifest..\nPlease consult README.txt for the correct configuration.", n));
					return false;
				}

				if (appContext.getPackageManager().checkPermission(n, appContext.getPackageName()) != PackageManager.PERMISSION_GRANTED) {
					// we don't have this permission, but it's in the manifest - user must have refused it.
					// Don't bitch, but don't construct OF either.
					return false;
				}
			}

		} catch (NameNotFoundException e) {
			Log.v(TAG, String.format("Couldn't find PackageInfo for %s.\nPlease initialize OF with an Activity that lives in your root package.", appContext.getPackageName()));
			return false;
		}
		
		return true;
	}
	
	private OpenFeintInternal(OpenFeintSettings settings, final Context ctx) {

		sInstance = this;
		mContext = ctx;
		
		mSettings = settings;
		
		SyncedStore.Reader r = getPrefs().read();
		try {
			mDeclined = (r.getString(getContext().getPackageName() + ".of_declined", null) != null);
		} finally {
			r.complete();
		}

		mMainThreadHandler = new Handler();
		mInternalProperties = new Properties();
		mInternalProperties.put("server-url", "https://api.openfeint.com");

		mInternalProperties.put("of-version", "1.6");

		loadPropertiesFromXMLResource(mInternalProperties, getResource("@xml/openfeint_internal_settings"));
		
		Log.i(TAG, "Using OpenFeint version " + mInternalProperties.get("of-version") +  " (" + mInternalProperties.get("server-url") + ")");

		Properties appProperties = new Properties();
		loadPropertiesFromXMLResource(appProperties, getResource("@xml/openfeint_app_settings"));
		mSettings.applyOverrides(appProperties);
		
		mSettings.verify();
		
		mClient = new Client(mSettings.key, mSettings.secret, getPrefs());
		Util.moveWebCache(ctx);
		
		WebViewCache.initialize(ctx);
		// create db after WebViewCache.initialize and before WebViewCache.start
		// so that we able to copy pre 1.5 db to sdcard
		DB.createDB(ctx);
		WebViewCache.start();
		analytics = new Analytics();
	}
	
	public Analytics getAnalytics() { return analytics; }
	
	private final User _lastLoggedInUser() {
		final User savedUser = loadUser();
		
		SyncedStore.Reader r = getPrefs().read();

		try {
			final URL saved = new URL(getServerUrl());
			final URL loaded = new URL(r.getString("last_logged_in_server", ""));
			if (savedUser != null && saved.equals(loaded))
				return savedUser;
		} catch (MalformedURLException e) {
		} finally {
			r.complete();
		}
		
		return null;
	}
	
	public void login() {
		// for safety, -always- delay this one tick.  We might get initialize()d, which fires off a login(),
		// but if we're resuming from saved state, that'll deserialize a logged-in user, so at that point
		// we'll want to bail on the login process.
		final Runnable r = new Runnable() {
			@Override public void run() {
				if (mDeclined || mCurrentlyLoggingIn || isUserLoggedIn()) {
					return;
				}
				
				// At this point, even though we aren't 'deserialized' per se, we are committed to logging in,
				// so deserialization should not be allowed to happen after this point.
				mDeserializedAlready = true;
				
				final User savedUser = _lastLoggedInUser();
				
				if (savedUser != null) {
					log(TAG, "Logging in last known user: "+ savedUser.name);
					
					loginUser(null, null, null, new IRawRequestDelegate() {
						@Override public void onResponse(int responseCode, String responseBody) {
							if (!(200 <= responseCode && responseCode < 300)) {
								if (403 == responseCode) {
									// This is a banned user or game.  Don't launch the intro flow.
									mBanned = true;
								} else {
									// Oops, there was a login failure - launch the normal intro flow.
									launchIntroFlow();
								}
							}
							else {
								SimpleNotification.show("Welcome back " + savedUser.name);
							}
						}
					});
				} else {
					log(TAG, "No last user, launch intro flow");
					clearPrefs();
					launchIntroFlow();
				}
			}
		};
		mMainThreadHandler.post(r);
	}
	
	private boolean checkBan() {
		if (mBanned) {
			displayErrorDialog(getContext().getText(R.string.of_banned_dialog));
			return true;
		}
		return false;
	}
	
	public void launchIntroFlow() {
		if (checkBan()) return;
		
		if(isFeintServerReachable()) {
			OpenFeintDelegate d = getDelegate();
			if (!mApproved && d != null && d.showCustomApprovalFlow(getContext())) {
				// Oh, we should wait on this to finish.
			} else {
				// We've either been custom-approved already, or there's no custom flow.
				
				final Runnable r = new Runnable() {
					@Override public void run() {
						final Intent i = new Intent(getContext(), IntroFlow.class);
						if (mApproved) {
							i.putExtra("content_name", "index?preapproved=true");
						}
						i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						getContext().startActivity(i);
					}
				};
				
				if (mCreatingDeviceSession || !mDeviceSessionCreated) {
					if (!mCreatingDeviceSession) {
						createDeviceSession();
					}
	
					mPostDeviceSessionRunnable = r;
					
				} else {
					r.run();
				}
			}
		}
		else {
			showOfflineNotification(0, "");
		}
	}

	private void showOfflineNotification(int httpCode, Object responseBody) {
		
		Resources r = getContext().getResources();
		String serverMessage = r.getString(R.string.of_offline_notification_line2);
		
		if (0 != httpCode) {
			if (403 == httpCode) {
				mBanned = true;
			}

			if (responseBody instanceof ServerException) {
				serverMessage = ((ServerException)responseBody).message;
			}
		}
		
		TwoLineNotification.show(r.getString(R.string.of_offline_notification), 
				serverMessage, 
				Notification.Category.Foreground, Notification.Type.NetworkOffline);
		
		log("Reachability", "Unable to launch IntroFlow because: " + serverMessage);
	}
	
	private void loadPropertiesFromXMLResource(Properties defaults, int resourceID) {
		XmlResourceParser xml = null;
		try {
			xml = getContext().getResources().getXml(resourceID);
		} catch (Exception e) {
		}
        if (xml != null) {
	        // terrible parser courtesy of it being Thursday
	        try {
	        	String k = null;
	        	for (int eventType = xml.getEventType(); xml.getEventType() != XmlPullParser.END_DOCUMENT; xml.next(), eventType = xml.getEventType()) {
					if (eventType == XmlPullParser.START_TAG) {
	        			k = xml.getName();
	        		} else if (xml.getEventType() == XmlPullParser.TEXT) {
	        			defaults.setProperty(k, xml.getText());
	        		}
	        	}
	        } catch (Exception e) {
	        	throw new RuntimeException(e);
	        }
        
	        xml.close();
        }
	}

	public boolean isFeintServerReachable() {
		ConnectivityManager conMan = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo activeNetwork = conMan.getActiveNetworkInfo();
		return activeNetwork != null && activeNetwork.isConnected();
	}
	
	public static void log(String tag, String message) {
		if (DEVELOPMENT_LOGGING_ENABLED) {
			Log.v(tag, message != null ? message : "(null)");
		}
	}
}
