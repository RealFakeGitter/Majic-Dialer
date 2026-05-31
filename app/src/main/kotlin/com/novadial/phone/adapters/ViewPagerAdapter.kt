package com.novadial.phone.adapters

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS
import org.fossify.commons.helpers.TAB_FAVORITES
import com.novadial.phone.R
import com.novadial.phone.activities.SimpleActivity
import com.novadial.phone.extensions.config
import com.novadial.phone.fragments.MyViewPagerFragment
import com.novadial.phone.helpers.tabsList

class ViewPagerAdapter(val activity: SimpleActivity) : PagerAdapter() {

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val layout = getFragment(position)
        val view = activity.layoutInflater.inflate(layout, container, false)
        container.addView(view)

        (view as MyViewPagerFragment<*>).apply {
            setupFragment(activity)
        }

        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
        container.removeView(item as View)
    }

    override fun getCount() = tabsList.filter { it and activity.config.showTabs != 0 }.size

    override fun isViewFromObject(view: View, item: Any) = view == item

    private fun getFragment(position: Int): Int {
        val showTabs = activity.config.showTabs
        val fragments = arrayListOf<Int>()
        for (tab in tabsList) {
            if (showTabs and tab > 0) {
                val layout = when (tab) {
                    TAB_FAVORITES -> R.layout.fragment_favorites
                    TAB_CALL_HISTORY -> R.layout.fragment_recents
                    TAB_CONTACTS -> R.layout.fragment_contacts
                    else -> 0
                }
                if (layout != 0) {
                    fragments.add(layout)
                }
            }
        }

        return if (position < fragments.size) fragments[position] else fragments.last()
    }
}
