package com.rafi.phonelink

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.security.SecureRandom

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun newCode(): String {
        val rnd = SecureRandom()
        return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "p"
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<EditTextPreference>("url")?.summaryProvider =
            EditTextPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>("quality")?.summaryProvider =
            ListPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>("fps")?.summaryProvider =
            ListPreference.SimpleSummaryProvider.getInstance()

        val prefs = preferenceManager.sharedPreferences
        val codePref = findPreference<Preference>("code_action")

        fun refreshCode() {
            codePref?.summary = "Current: " + (prefs?.getString("code", "") ?: "") + "  ·  tap to generate a new one"
        }
        refreshCode()

        codePref?.setOnPreferenceClickListener {
            val c = newCode()
            prefs?.edit()?.putString("code", c)?.apply()
            refreshCode()
            Toast.makeText(requireContext(), "New pairing code: $c", Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("reset_action")?.setOnPreferenceClickListener {
            prefs?.edit()?.clear()?.apply()
            Toast.makeText(requireContext(), "Cleared. Closing.", Toast.LENGTH_SHORT).show()
            requireActivity().finishAffinity()
            true
        }

        val versionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) {
            "unknown"
        }
        findPreference<Preference>("about_version")?.summary = versionName
    }
}
