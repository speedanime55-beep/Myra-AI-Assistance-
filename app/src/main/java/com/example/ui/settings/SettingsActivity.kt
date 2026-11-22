package com.example.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.databinding.ActivitySettingsBinding
import com.example.model.MyraPrefs
import com.example.model.PrimeContact
import com.example.service.AccessibilityHelperService

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val primeContacts = mutableListOf<PrimeContact>()
    private lateinit var primeAdapter: PrimeContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupSpinners()
        setupPrimeContacts()
        loadSavedSettings()
        wireListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshSystemStatus()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupSpinners() {
        val modelLabels = MyraPrefs.AVAILABLE_MODELS.map { it.label }
        val modelAdapter = ArrayAdapter(this, R.layout.spinner_item, modelLabels).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        binding.modelSpinner.adapter = modelAdapter

        val voiceLabels = MyraPrefs.AVAILABLE_VOICES.map { it.displayName }
        val voiceAdapter = ArrayAdapter(this, R.layout.spinner_item, voiceLabels).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        binding.voiceSpinner.adapter = voiceAdapter
    }

    private fun setupPrimeContacts() {
        primeContacts.clear()
        primeContacts.addAll(MyraPrefs.getPrimeContacts(this))

        primeAdapter = PrimeContactAdapter(primeContacts) { position ->
            if (position in primeContacts.indices) {
                primeContacts.removeAt(position)
                primeAdapter.notifyItemRemoved(position)
                primeAdapter.notifyItemRangeChanged(position, primeContacts.size)
                MyraPrefs.savePrimeContacts(this, primeContacts)
                updatePrimeEmptyState()
            }
        }
        binding.primeContactsRecycler.layoutManager = LinearLayoutManager(this)
        binding.primeContactsRecycler.adapter = primeAdapter
        updatePrimeEmptyState()
    }

    private fun updatePrimeEmptyState() {
        binding.primeEmptyText.visibility = if (primeContacts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadSavedSettings() {
        binding.apiKeyInput.setText(MyraPrefs.getSavedUserApiKey(this))
        binding.userNameInput.setText(MyraPrefs.getRawUserName(this))

        val savedModel = MyraPrefs.getModel(this)
        val modelIdx = MyraPrefs.AVAILABLE_MODELS.indexOfFirst { it.modelId == savedModel }.coerceAtLeast(0)
        binding.modelSpinner.setSelection(modelIdx)

        val savedVoice = MyraPrefs.getVoice(this)
        val voiceIdx = MyraPrefs.AVAILABLE_VOICES.indexOfFirst { it.voiceId == savedVoice }.coerceAtLeast(0)
        binding.voiceSpinner.setSelection(voiceIdx)

        when (MyraPrefs.getPersonalityMode(this)) {
            MyraPrefs.MODE_PROFESSIONAL -> binding.radioProfessional.isChecked = true
            MyraPrefs.MODE_ASSISTANT -> binding.radioAssistant.isChecked = true
            else -> binding.radioGf.isChecked = true
        }
    }

    private fun wireListeners() {
        binding.backBtn.setOnClickListener { finish() }

        binding.addPrimeContactBtn.setOnClickListener {
            showAddPrimeContactDialog()
        }

        binding.accessibilityStatusText.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open Accessibility Settings", Toast.LENGTH_SHORT).show()
            }
        }

        binding.overlayStatusText.setOnClickListener {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open Overlay Settings", Toast.LENGTH_SHORT).show()
            }
        }

        binding.saveSettingsBtn.setOnClickListener {
            saveAllSettings()
        }
    }

    private fun showAddPrimeContactDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_prime_contact, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.dialogNameInput)
        val numberInput = dialogView.findViewById<EditText>(R.id.dialogNumberInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_prime_title)
            .setView(dialogView)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val name = nameInput.text.toString().trim()
                val number = numberInput.text.toString().trim()
                if (name.isEmpty() || number.isEmpty()) {
                    Toast.makeText(this, "Please enter both name and number", Toast.LENGTH_SHORT).show()
                } else {
                    primeContacts.add(PrimeContact(name, number))
                    primeAdapter.notifyItemInserted(primeContacts.size - 1)
                    MyraPrefs.savePrimeContacts(this, primeContacts)
                    updatePrimeEmptyState()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun refreshSystemStatus() {
        val accEnabled = AccessibilityHelperService.isEnabled(this)
        if (accEnabled) {
            binding.accessibilityStatusText.text = getString(R.string.accessibility_enabled)
            binding.accessibilityStatusText.setTextColor(ContextCompat.getColor(this, R.color.myra_success))
        } else {
            binding.accessibilityStatusText.text = getString(R.string.accessibility_disabled)
            binding.accessibilityStatusText.setTextColor(ContextCompat.getColor(this, R.color.myra_red))
        }

        val overlayEnabled = Settings.canDrawOverlays(this)
        if (overlayEnabled) {
            binding.overlayStatusText.text = getString(R.string.overlay_enabled)
            binding.overlayStatusText.setTextColor(ContextCompat.getColor(this, R.color.myra_success))
        } else {
            binding.overlayStatusText.text = getString(R.string.overlay_disabled)
            binding.overlayStatusText.setTextColor(ContextCompat.getColor(this, R.color.myra_red))
        }
    }

    private fun saveAllSettings() {
        val apiKey = binding.apiKeyInput.text.toString().trim()
        val userName = binding.userNameInput.text.toString().trim()
        val selectedModel = MyraPrefs.AVAILABLE_MODELS.getOrNull(binding.modelSpinner.selectedItemPosition)?.modelId
            ?: MyraPrefs.DEFAULT_MODEL
        val selectedVoice = MyraPrefs.AVAILABLE_VOICES.getOrNull(binding.voiceSpinner.selectedItemPosition)?.voiceId
            ?: MyraPrefs.DEFAULT_VOICE

        val personality = when (binding.personalityGroup.checkedRadioButtonId) {
            R.id.radioProfessional -> MyraPrefs.MODE_PROFESSIONAL
            R.id.radioAssistant -> MyraPrefs.MODE_ASSISTANT
            else -> MyraPrefs.MODE_GF
        }

        MyraPrefs.prefs(this).edit()
            .putString(MyraPrefs.KEY_API_KEY, apiKey)
            .putString(MyraPrefs.KEY_USER_NAME, userName)
            .putString(MyraPrefs.KEY_GEMINI_MODEL, selectedModel)
            .putString(MyraPrefs.KEY_GEMINI_VOICE, selectedVoice)
            .putString(MyraPrefs.KEY_PERSONALITY_MODE, personality)
            .apply()

        MyraPrefs.savePrimeContacts(this, primeContacts)

        Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }
}

class PrimeContactAdapter(
    private val items: List<PrimeContact>,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<PrimeContactAdapter.PrimeViewHolder>() {

    class PrimeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.primeItemName)
        val numberText: TextView = view.findViewById(R.id.primeItemNumber)
        val deleteBtn: ImageButton = view.findViewById(R.id.primeItemDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PrimeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_prime_contact, parent, false)
        return PrimeViewHolder(view)
    }

    override fun onBindViewHolder(holder: PrimeViewHolder, position: Int) {
        val item = items[position]
        holder.nameText.text = item.name
        holder.numberText.text = item.number
        holder.deleteBtn.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onDeleteClick(adapterPos)
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
