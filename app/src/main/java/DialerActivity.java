import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class DialerActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dialer);

        // Start the floating button service when a call is active
        Intent serviceIntent = new Intent(this, FloatingButtonService.class);
        startService(serviceIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop the floating button service when the call ends
        Intent serviceIntent = new Intent(this, FloatingButtonService.class);
        stopService(serviceIntent);
    }
}
