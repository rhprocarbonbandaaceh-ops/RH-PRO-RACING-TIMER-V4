package com.rhpro.racetimer

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.*
import com.google.firebase.auth.*
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private val db by lazy { FirebaseFirestore.getInstance() }
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var speedView: TextView
    private lateinit var timerView: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var runStatus: TextView
    private lateinit var targetInput: EditText
    private lateinit var authPanel: LinearLayout
    private lateinit var accountView: TextView
    private lateinit var historyView: TextView
    private lateinit var logoutButton: Button
    private var startNs: Long? = null
    private var running = false
    private val triggerKmh = 10.0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (loc.accuracy > 25f) { gpsStatus.text = "GPS: AKURASI RENDAH"; return }
            val kmh = (loc.speed * 3.6).coerceAtLeast(0.0)
            speedView.text = String.format(Locale.US, "%.1f", kmh)
            gpsStatus.text = "GPS AKTIF • AKURASI ${loc.accuracy.toInt()} m"
            val target = targetInput.text.toString().toDoubleOrNull()?.takeIf { it > triggerKmh } ?: 60.0
            if (!running && startNs == null && kmh >= triggerKmh) {
                startNs = SystemClock.elapsedRealtimeNanos()
                running = true
                runStatus.text = "MENGUKUR MENUJU ${target.toInt()} KM/JAM"
            }
            val start = startNs
            if (running && start != null) {
                val elapsed = SystemClock.elapsedRealtimeNanos() - start
                timerView.text = String.format(Locale.US, "%.3f s", elapsed / 1e9)
                if (kmh >= target) {
                    running = false
                    runStatus.text = "SELESAI • TARGET ${target.toInt()} KM/JAM"
                    saveRun(elapsed, kmh, loc.accuracy)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        auth = FirebaseAuth.getInstance(); fused = LocationServices.getFusedLocationProviderClient(this)
        email = findViewById(R.id.email); password = findViewById(R.id.password)
        speedView = findViewById(R.id.speed); timerView = findViewById(R.id.timer)
        gpsStatus = findViewById(R.id.gpsStatus); runStatus = findViewById(R.id.runStatus)
        targetInput = findViewById(R.id.targetSpeed); authPanel = findViewById(R.id.authPanel)
        accountView = findViewById(R.id.account); historyView = findViewById(R.id.history)
        logoutButton = findViewById(R.id.logout)
        findViewById<Button>(R.id.emailLogin).setOnClickListener { emailAuth() }
        findViewById<Button>(R.id.googleLogin).setOnClickListener { googleLogin() }
        logoutButton.setOnClickListener { auth.signOut(); stopGps(); updateAuthUi() }
        findViewById<Button>(R.id.reset).setOnClickListener { startNs = null; running = false; timerView.text = "0.000 s"; runStatus.text = "SIAP • PEMICU 10 KM/JAM" }
        updateAuthUi()
    }

    private fun emailAuth() {
        val mail = email.text.toString().trim(); val pass = password.text.toString()
        if (mail.isBlank() || pass.length < 6) { toast("Masukkan email dan password minimal 6 karakter"); return }
        auth.signInWithEmailAndPassword(mail, pass).addOnCompleteListener { task ->
            if (task.isSuccessful) { toast("Login berhasil"); updateAuthUi() }
            else auth.createUserWithEmailAndPassword(mail, pass).addOnCompleteListener { create ->
                if (create.isSuccessful) { toast("Akun berhasil dibuat"); updateAuthUi() }
                else toast(create.exception?.localizedMessage ?: "Login gagal")
            }
        }
    }

    private fun googleLogin() {
        val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build()
        @Suppress("DEPRECATION")
        startActivityForResult(GoogleSignIn.getClient(this, opts).signInIntent, 9001)
    }

    @Deprecated("Legacy Google Sign-In result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9001) try {
            val acct = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            auth.signInWithCredential(GoogleAuthProvider.getCredential(acct.idToken, null)).addOnCompleteListener {
                if (it.isSuccessful) { toast("Login Google berhasil"); updateAuthUi() } else toast("Login Google gagal")
            }
        } catch (_: Exception) { toast("Login Google dibatalkan atau gagal") }
    }

    private fun updateAuthUi() {
        val user = auth.currentUser
        authPanel.visibility = if (user == null) View.VISIBLE else View.GONE
        accountView.visibility = if (user == null) View.GONE else View.VISIBLE
        logoutButton.visibility = if (user == null) View.GONE else View.VISIBLE
        accountView.text = "AKUN: ${user?.email ?: ""}"
        if (user != null) { loadHistory(); startGps() }
    }

    private fun startGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 77); return
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L).setMinUpdateIntervalMillis(300L).build()
        fused.requestLocationUpdates(req, callback, mainLooper).addOnFailureListener { gpsStatus.text = "GPS GAGAL: ${it.localizedMessage}" }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGps()
        else gpsStatus.text = "IZIN LOKASI DIBUTUHKAN"
    }

    private fun saveRun(elapsed: Long, speed: Double, accuracy: Float) {
        val user = auth.currentUser ?: return
        val row = hashMapOf<String, Any>(
            "elapsedSeconds" to elapsed / 1e9, "triggerKmh" to triggerKmh,
            "targetKmh" to (targetInput.text.toString().toDoubleOrNull() ?: 60.0),
            "finishSpeedKmh" to speed, "gpsAccuracyMeters" to accuracy.toDouble(),
            "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        db.collection("users").document(user.uid).collection("runs").add(row)
            .addOnSuccessListener { loadHistory() }.addOnFailureListener { toast("Gagal simpan data: ${it.localizedMessage}") }
    }

    private fun loadHistory() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).collection("runs")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(10).get()
            .addOnSuccessListener { snap ->
                val rows = snap.documents.mapIndexed { i, d ->
                    "${i + 1}. ${d.getDouble("elapsedSeconds")?.let { String.format(Locale.US, "%.3f s", it) } ?: "--"}"
                }
                historyView.text = "RIWAYAT PENGUKURAN\n" + if (rows.isEmpty()) "Belum ada data" else rows.joinToString("\n")
            }
    }

    private fun stopGps() { fused.removeLocationUpdates(callback) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onDestroy() { stopGps(); super.onDestroy() }
}
