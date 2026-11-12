package dev.kmpcoach.ide

import com.intellij.openapi.util.IconLoader
import dev.kmpcoach.core.Brand
import com.intellij.ui.ColoredListCellRenderer
import java.io.File
import javax.swing.Icon
import javax.swing.JList
import javax.swing.ListCellRenderer

/** Los íconos de cada IA (`/icons/brands/<id>.svg`), para el selector de proveedor, el modelo y el contexto del panel. */
object BrandIcons {
    fun of(brand: Brand): Icon = IconLoader.getIcon("/icons/brands/${brand.id}.svg", BrandIcons::class.java)
    fun ofProvider(providerId: String?): Icon = of(Brand.ofProvider(providerId))
    fun ofPlanFile(f: File): Icon = of(Brand.ofPlanFile(f.path))

    /**
     * Dibuja cada fila de una lista con su ícono y su texto. Es la forma estable (`SimpleListCellRenderer.create` está
     * marcada para eliminarse en versiones futuras del IDE).
     */
    fun <T> renderer(text: (T) -> String, icon: (T) -> Icon?): ListCellRenderer<T?> = object : ColoredListCellRenderer<T>() {
        override fun customizeCellRenderer(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
            if (value == null) return
            this.icon = icon(value)
            append(text(value))
        }
    }
}
