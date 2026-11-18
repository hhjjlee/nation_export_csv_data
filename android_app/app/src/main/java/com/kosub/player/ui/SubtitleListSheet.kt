package com.kosub.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.kosub.player.core.SubUnit
import com.kosub.player.core.Subtitles
import com.kosub.player.databinding.ItemSubtitleBinding
import com.kosub.player.databinding.SheetSubtitlesBinding
import kotlinx.coroutines.launch

/** 만들어진 자막 목록: 누르면 이동, 길게 누르면 번역 수정. */
class SubtitleListSheet : BottomSheetDialogFragment() {
    private val vm: PlayerViewModel by activityViewModels()
    private var _binding: SheetSubtitlesBinding? = null
    private val adapter = Adapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetSubtitlesBinding.inflate(inflater, container, false)
        _binding = b
        b.subtitleList.layoutManager = LinearLayoutManager(requireContext())
        b.subtitleList.adapter = adapter
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.units.collect { list ->
                    adapter.submit(list)
                    _binding?.sheetTitle?.text = "자막 목록 (${list.size}개)"
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun editDialog(unit: SubUnit) {
        val input = EditText(requireContext()).apply {
            setText(unit.ko ?: "")
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("번역 수정")
            .setMessage(unit.text)
            .setView(input)
            .setPositiveButton("저장") { _, _ -> vm.edit(unit.id, input.text.toString()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<SubUnit> = emptyList()

        fun submit(list: List<SubUnit>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(val b: ItemSubtitleBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSubtitleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val u = items[position]
            holder.b.itemTime.text = Subtitles.formatClock(u.start)
            holder.b.itemKo.text = u.ko ?: "(번역 대기 중…)"
            holder.b.itemKo.alpha = if (u.ko == null) 0.6f else 1f
            holder.b.itemSrc.text = u.text
            holder.b.itemSrc.visibility = if (u.ko == u.text) View.GONE else View.VISIBLE
            holder.b.itemRoot.setOnClickListener {
                vm.requestSeek(u.start)
                dismiss()
            }
            holder.b.itemRoot.setOnLongClickListener {
                editDialog(u); true
            }
        }
    }
}
