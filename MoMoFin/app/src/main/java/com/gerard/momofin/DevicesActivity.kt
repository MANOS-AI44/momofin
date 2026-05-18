package com.gerard.momofin

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gerard.momofin.databinding.ActivityDevicesBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DevicesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevicesBinding
    private lateinit var adapter: Adapter
    private val devices = mutableListOf<RemoteDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = Adapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnNew.setOnClickListener { askNewDevice() }

        if (!Settings.isConfigured(this)) {
            Toast.makeText(this, "Connectez-vous d'abord", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        loadList()
    }

    private fun loadList() {
        binding.txtStatus.text = "Chargement…"
        CoroutineScope(Dispatchers.IO).launch {
            val list = DevicesClient.listAll(Settings.getUrl(this@DevicesActivity), Settings.getToken(this@DevicesActivity))
            withContext(Dispatchers.Main) {
                devices.clear(); devices.addAll(list); adapter.notifyDataSetChanged()
                binding.txtStatus.text = "${list.size} appareil(s) inscrit(s)"
                binding.txtEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun askNewDevice() {
        val edit = EditText(this).apply {
            hint = "Nom (ex. Boutique 1, Boutique 2…)"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Ajouter un appareil")
            .setMessage("Donnez un nom (ex. Boutique 1) et vous recevrez un code à transmettre à l'utilisateur de ce téléphone.")
            .setView(edit)
            .setPositiveButton("Créer") { _, _ ->
                val label = edit.text.toString().trim().ifBlank { "Téléphone" }
                createDevice(label)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun createDevice(label: String) {
        binding.txtStatus.text = "Création en cours…"
        CoroutineScope(Dispatchers.IO).launch {
            val d = DevicesClient.create(Settings.getUrl(this@DevicesActivity), Settings.getToken(this@DevicesActivity), label)
            withContext(Dispatchers.Main) {
                if (d == null) {
                    Toast.makeText(this@DevicesActivity, "Erreur lors de la création", Toast.LENGTH_LONG).show()
                    binding.txtStatus.text = ""
                    return@withContext
                }
                showCodeDialog(d)
                loadList()
            }
        }
    }

    private fun showCodeDialog(d: RemoteDevice) {
        val msg = "Appareil créé : ${d.label}\n\n" +
                  "Code à transmettre :\n\n" +
                  "        ${d.code}\n\n" +
                  "L'utilisateur doit ouvrir l'application MoMo Fin sur son téléphone,\n" +
                  "puis choisir \"🔢 J'ai un code d'appareil\" et saisir ce code."

        AlertDialog.Builder(this)
            .setTitle("✅ Code généré")
            .setMessage(msg)
            .setPositiveButton("Copier le code") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Code MoMo Fin", d.code))
                Toast.makeText(this, "Code ${d.code} copié", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun confirmDelete(d: RemoteDevice) {
        AlertDialog.Builder(this)
            .setTitle("Révoquer cet appareil ?")
            .setMessage("Le téléphone \"${d.label}\" ne pourra plus synchroniser. Vous pouvez recréer un nouveau code à tout moment.")
            .setPositiveButton("Révoquer") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    val ok = DevicesClient.delete(Settings.getUrl(this@DevicesActivity), Settings.getToken(this@DevicesActivity), d.token)
                    withContext(Dispatchers.Main) {
                        if (ok) loadList()
                        else Toast.makeText(this@DevicesActivity, "Impossible de révoquer (peut-être l'appareil actuel)", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.txtLabel)
            val code: TextView = v.findViewById(R.id.txtCode)
            val current: TextView = v.findViewById(R.id.txtCurrent)
        }
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_device, p, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val d = devices[pos]
            h.label.text = d.label
            h.code.text = d.code.ifBlank { "—" }
            val isThisPhone = (d.token == Settings.getToken(this@DevicesActivity))
            h.current.visibility = if (isThisPhone) View.VISIBLE else View.GONE
            h.itemView.setOnLongClickListener {
                if (!isThisPhone) confirmDelete(d)
                else Toast.makeText(this@DevicesActivity, "Impossible de supprimer le téléphone actuel", Toast.LENGTH_SHORT).show()
                true
            }
        }
        override fun getItemCount() = devices.size
    }
}
