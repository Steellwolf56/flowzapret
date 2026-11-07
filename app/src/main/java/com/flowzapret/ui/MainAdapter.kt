package com.flowzapret.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.flowzapret.activities.PageBypassFragment
import com.flowzapret.activities.TelegramFragment

/**
 * Две фиксированные вкладки: «Обход DPI» и «Telegram».
 */
class MainAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private val titles = mutableListOf<String>()

    fun addPage(title: String) {
        titles.add(title)
    }

    override fun getItemCount(): Int = titles.size

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> PageBypassFragment()
        1 -> TelegramFragment()
        else -> throw IllegalArgumentException("Unknown page $position")
    }

    fun getPageTitle(position: Int): String = titles[position]
}
