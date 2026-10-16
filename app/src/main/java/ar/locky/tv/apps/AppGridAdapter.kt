package ar.locky.tv.apps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import ar.locky.tv.R

/** Arma cada "casillero" de la grilla de apps. */
class AppGridAdapter(private val apps: List<TvApp>) : BaseAdapter() {

    override fun getCount() = apps.size
    override fun getItem(position: Int): Any = apps[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val app = apps[position]

        val image = view.findViewById<ImageView>(R.id.appImage)
        image.setImageDrawable(app.image)
        if (app.isBanner) {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setPadding(0, 0, 0, 0)
        } else {
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (22 * parent.resources.displayMetrics.density).toInt()
            image.setPadding(pad, pad, pad, pad)
        }
        view.findViewById<TextView>(R.id.appLabel).text = app.label
        return view
    }
}
