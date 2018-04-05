package YOUR_PACKAGE_NAME;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.applovin.adview.AppLovinAdView;
import com.applovin.adview.AppLovinAdViewEventListener;
import com.applovin.sdk.AppLovinAd;
import com.applovin.sdk.AppLovinAdClickListener;
import com.applovin.sdk.AppLovinAdDisplayListener;
import com.applovin.sdk.AppLovinAdLoadListener;
import com.applovin.sdk.AppLovinAdSize;
import com.applovin.sdk.AppLovinErrorCodes;
import com.applovin.sdk.AppLovinSdk;
import com.applovin.sdk.AppLovinSdkSettings;
import com.mopub.mobileads.CustomEventBanner;
import com.mopub.mobileads.MoPubErrorCode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static android.util.Log.DEBUG;
import static android.util.Log.ERROR;

/**
 * AppLovin SDK banner adapter for MoPub.
 * <p>
 * Created by Thomas So on 3/6/17.
 */

//
// PLEASE NOTE: We have renamed this class from "YOUR_PACKAGE_NAME.AppLovinBannerAdapter" to "YOUR_PACKAGE_NAME.AppLovinCustomEventBanner", you can use either classname in your MoPub account.
//
public class AppLovinCustomEventBanner
        extends CustomEventBanner
{
    private static final boolean LOGGING_ENABLED = true;
    private static final Handler UI_HANDLER      = new Handler( Looper.getMainLooper() );

    private static final int BANNER_STANDARD_HEIGHT         = 50;
    private static final int BANNER_HEIGHT_OFFSET_TOLERANCE = 10;
    private static final int LEADER_STANDARD_HEIGHT         = 90;
    private static final int LEADER_HEIGHT_OFFSET_TOLERANCE = 16;

    private static final String AD_WIDTH_KEY  = "com_mopub_ad_width";
    private static final String AD_HEIGHT_KEY = "com_mopub_ad_height";

    private AppLovinSdk sdk;

    //
    // MoPub Custom Event Methods
    //

    @Override
    protected void loadBanner(final Context context, final CustomEventBannerListener customEventBannerListener, final Map<String, Object> localExtras, final Map<String, String> serverExtras)
    {
        // SDK versions BELOW 7.1.0 require a instance of an Activity to be passed in as the context
        if ( AppLovinSdk.VERSION_CODE < 710 && !( context instanceof Activity ) )
        {
            log( ERROR, "Unable to request AppLovin banner. Invalid context provided." );
            customEventBannerListener.onBannerFailed( MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR );

            return;
        }

        log( DEBUG, "Requesting AppLovin banner with serverExtras: " + serverExtras + " and localExtras: " + localExtras );

        final AppLovinAdSize adSize = appLovinAdSizeFromLocalExtras( localExtras );
        if ( adSize != null )
        {
            sdk = retrieveSdk( serverExtras, context );
            sdk.setPluginVersion( "MoPub-2.1.3" );

            final AppLovinAdView adView = new AppLovinAdView( sdk, adSize, context );
            adView.setAdDisplayListener( new AppLovinAdDisplayListener()
            {
                @Override
                public void adDisplayed(final AppLovinAd ad)
                {
                    log( DEBUG, "Banner displayed" );
                }

                @Override
                public void adHidden(final AppLovinAd ad)
                {
                    log( DEBUG, "Banner dismissed" );
                }
            } );
            adView.setAdClickListener( new AppLovinAdClickListener()
            {
                @Override
                public void adClicked(final AppLovinAd ad)
                {
                    log( DEBUG, "Banner clicked" );

                    customEventBannerListener.onBannerClicked();
                    customEventBannerListener.onLeaveApplication();
                }
            } );

            // As of Android SDK >= 7.3.0, we added a listener for banner events
            if ( AppLovinSdk.VERSION_CODE >= 730 )
            {
                adView.setAdViewEventListener( (AppLovinAdViewEventListener) AppLovinAdViewEventListenerProxy.newInstance( customEventBannerListener ) );
            }

            final AppLovinAdLoadListener adLoadListener = new AppLovinAdLoadListener()
            {
                @Override
                public void adReceived(final AppLovinAd ad)
                {
                    // Ensure logic is ran on main queue
                    runOnUiThread( new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            adView.renderAd( ad );

                            log( DEBUG, "Successfully loaded banner ad" );
                            customEventBannerListener.onBannerLoaded( adView );
                        }
                    } );
                }

                @Override
                public void failedToReceiveAd(final int errorCode)
                {
                    // Ensure logic is ran on main queue
                    runOnUiThread( new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            log( ERROR, "Failed to load banner ad with code: " + errorCode );
                            customEventBannerListener.onBannerFailed( toMoPubErrorCode( errorCode ) );
                        }
                    } );
                }
            };

            // Zones support is available on AppLovin SDK 7.5.0 and higher
            final String zoneId;
            if ( AppLovinSdk.VERSION_CODE >= 750 && serverExtras != null && serverExtras.containsKey( "zone_id" ) )
            {
                zoneId = serverExtras.get( "zone_id" );
            }
            else
            {
                zoneId = null;
            }

            if ( !TextUtils.isEmpty( zoneId ) )
            {
                loadNextAd( zoneId, adLoadListener, customEventBannerListener );
            }
            else
            {
                sdk.getAdService().loadNextAd( adSize, adLoadListener );
            }
        }
        else
        {
            log( ERROR, "Unable to request AppLovin banner" );

            customEventBannerListener.onBannerFailed( MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR );
        }
    }

    @Override
    protected void onInvalidate() {}

    //
    // Utility Methods
    //

    private AppLovinAdSize appLovinAdSizeFromLocalExtras(final Map<String, Object> localExtras)
    {
        // Handle trivial case
        if ( localExtras == null || localExtras.isEmpty() )
        {
            log( ERROR, "No serverExtras provided" );
            return null;
        }

        try
        {
            final int width = (Integer) localExtras.get( AD_WIDTH_KEY );
            final int height = (Integer) localExtras.get( AD_HEIGHT_KEY );

            // We have valid dimensions
            if ( width > 0 && height > 0 )
            {
                log( DEBUG, "Valid width (" + width + ") and height (" + height + ") provided" );

                // Assume fluid width, and check for height with offset tolerance
                final int bannerOffset = Math.abs( BANNER_STANDARD_HEIGHT - height );
                final int leaderOffset = Math.abs( LEADER_STANDARD_HEIGHT - height );

                if ( bannerOffset <= BANNER_HEIGHT_OFFSET_TOLERANCE )
                {
                    return AppLovinAdSize.BANNER;
                }
                else if ( leaderOffset <= LEADER_HEIGHT_OFFSET_TOLERANCE )
                {
                    return AppLovinAdSize.LEADER;
                }
                else if ( height <= AppLovinAdSize.MREC.getHeight() )
                {
                    return AppLovinAdSize.MREC;
                }
                else
                {
                    log( ERROR, "Provided dimensions does not meet the dimensions required of banner or mrec ads" );
                }
            }
            else
            {
                log( ERROR, "Invalid width (" + width + ") and height (" + height + ") provided" );
            }
        }
        catch ( Throwable th )
        {
            log( ERROR, "Encountered error while parsing width and height from serverExtras", th );
        }

        return null;
    }

    //
    // Utility Methods
    //

    private static void log(final int priority, final String message)
    {
        log( priority, message, null );
    }

    private static void log(final int priority, final String message, final Throwable th)
    {
        if ( LOGGING_ENABLED )
        {
            Log.println( priority, "AppLovinBanner", message + ( ( th == null ) ? "" : Log.getStackTraceString( th ) ) );
        }
    }

    private static MoPubErrorCode toMoPubErrorCode(final int applovinErrorCode)
    {
        if ( applovinErrorCode == AppLovinErrorCodes.NO_FILL )
        {
            return MoPubErrorCode.NETWORK_NO_FILL;
        }
        else if ( applovinErrorCode == AppLovinErrorCodes.UNSPECIFIED_ERROR )
        {
            return MoPubErrorCode.NETWORK_INVALID_STATE;
        }
        else if ( applovinErrorCode == AppLovinErrorCodes.NO_NETWORK )
        {
            return MoPubErrorCode.NO_CONNECTION;
        }
        else if ( applovinErrorCode == AppLovinErrorCodes.FETCH_AD_TIMEOUT )
        {
            return MoPubErrorCode.NETWORK_TIMEOUT;
        }
        else
        {
            return MoPubErrorCode.UNSPECIFIED;
        }
    }

    /**
     * Dynamic proxy class for AppLovin's AppLovinAdViewEventListener. Used to keep compilation compatibility if publisher is on a version of the SDK before the listener was introduced (< 7.3.0).
     */
    private static final class AppLovinAdViewEventListenerProxy
            implements InvocationHandler
    {
        private final CustomEventBannerListener customEventBannerListener;

        private static Object newInstance(final CustomEventBannerListener customEventBannerListener)
        {
            return Proxy.newProxyInstance( AppLovinAdViewEventListener.class.getClassLoader(),
                                           new Class[] { AppLovinAdViewEventListener.class },
                                           new AppLovinAdViewEventListenerProxy( customEventBannerListener ) );
        }

        private AppLovinAdViewEventListenerProxy(final CustomEventBannerListener customEventBannerListener)
        {
            this.customEventBannerListener = customEventBannerListener;
        }

        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable
        {
            final String methodName = method.getName();

            if ( "adOpenedFullscreen".equals( methodName ) )
            {
                log( DEBUG, "Banner opened fullscreen" );
                customEventBannerListener.onBannerExpanded();
            }
            else if ( "adClosedFullscreen".equals( methodName ) )
            {
                log( DEBUG, "Banner closed fullscreen" );
                customEventBannerListener.onBannerCollapsed();
            }
            else if ( "adLeftApplication".equals( methodName ) )
            {
                // We will fire onLeaveApplication() in the adClicked() callback
                log( DEBUG, "Banner left application" );
            }
            else if ( "adFailedToDisplay".equals( methodName ) ) {}

            return null;
        }
    }

    /**
     * Retrieves the appropriate instance of AppLovin's SDK from the SDK key given in the server parameters, or Android Manifest.
     */
    static AppLovinSdk retrieveSdk(final Map<String, String> serverExtras, final Context context)
    {
        final String sdkKey = serverExtras != null ? serverExtras.get( "sdk_key" ) : null;
        final AppLovinSdk sdk;

        if ( !TextUtils.isEmpty( sdkKey ) )
        {
            sdk = AppLovinSdk.getInstance( sdkKey, new AppLovinSdkSettings(), context );
        }
        else
        {
            sdk = AppLovinSdk.getInstance( context );
        }

        return sdk;
    }

    private void loadNextAd(final String zoneId, final AppLovinAdLoadListener adLoadListener, final CustomEventBannerListener customEventBannerListener)
    {
        // Dynamically load an ad for a given zone without breaking backwards compatibility for publishers on older SDKs
        try
        {
            final Method method = sdk.getAdService().getClass().getMethod( "loadNextAdForZoneId", String.class, AppLovinAdLoadListener.class );
            method.invoke( sdk.getAdService(), zoneId, adLoadListener );
        }
        catch ( Throwable th )
        {
            log( ERROR, "Unable to load ad for zone: " + zoneId + "..." );
            customEventBannerListener.onBannerFailed( MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR );
        }
    }

    /**
     * Performs the given runnable on the main thread.
     */
    public static void runOnUiThread(final Runnable runnable)
    {
        if ( Looper.myLooper() == Looper.getMainLooper() )
        {
            runnable.run();
        }
        else
        {
            UI_HANDLER.post( runnable );
        }
    }
}
