package com.easysavevideo.app.library

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.*
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

/** Actual Foundation ScrollNode measurement, synthetic plain content; no glyph/OEM rendering. */
class LibraryDialogMeasureTest {
    private class Scope(override val fontScale:Float):MeasureScope {
        override val density=1f
        override val layoutDirection=LayoutDirection.Ltr
    }
    private class Box(private val desiredHeight:Int,private val nested:LayoutModifierNode?=null,
        private val scope:MeasureScope):Measurable {
        override val parentData:Any?=null
        override fun minIntrinsicWidth(height:Int)=100
        override fun maxIntrinsicWidth(height:Int)=100
        override fun minIntrinsicHeight(width:Int)=desiredHeight
        override fun maxIntrinsicHeight(width:Int)=desiredHeight
        override fun measure(constraints:Constraints):Placeable {
            val result=nested?.let { node -> with(node) { scope.measure(Box(desiredHeight,scope=scope),constraints) } }
            val width=result?.width ?: constraints.constrainWidth(100)
            val height=result?.height ?: constraints.constrainHeight(desiredHeight)
            return object:Placeable() {
                init { measurementConstraints=constraints;measuredSize=IntSize(width,height) }
                override fun get(alignmentLine:AlignmentLine)=AlignmentLine.Unspecified
                override fun placeAt(position:IntOffset,zIndex:Float,layerBlock:(GraphicsLayerScope.()->Unit)?)=Unit
            }
        }
    }
    private fun scrollNode(state:ScrollState):LayoutModifierNode {
        val element=Modifier.verticalScroll(state).foldIn<ModifierNodeElement<*>?>(null) { found,current ->
            if(current.javaClass.simpleName=="ScrollingLayoutElement")current as ModifierNodeElement<*> else found
        }
        return requireNotNull(element).create() as LayoutModifierNode
    }
    @Test fun oldNestedScrollHierarchyReproducesTheDeviceException() {
        val scope=Scope(1f);val outer=scrollNode(ScrollState(0));val inner=scrollNode(ScrollState(0))
        try { with(outer){scope.measure(Box(1000,inner,scope),Constraints(maxWidth=320,maxHeight=720))};fail() }
        catch(failure:IllegalStateException){assertTrue(failure.message.orEmpty().contains("infinity maximum height"))}
    }
    @Test fun oneScrollOwnerMeasuresAndCanReachEndForLongListsLargeFontsAndAllDestinations() {
        for(destination in LibraryExportDestination.entries)for(items in listOf(1,50,100))
            for(font in listOf(1f,2f,3.2f))for(viewport in listOf(160,360,720)) {
                val scope=Scope(font);val state=ScrollState(0);val outer=scrollNode(state)
                val contentHeight=ceil((items*48+520)*font).toInt()
                val result=with(outer){scope.measure(Box(contentHeight,scope=scope),Constraints(maxWidth=320,maxHeight=viewport))}
                assertEquals(destination.name,minOf(contentHeight,viewport),result.height)
                assertEquals(maxOf(0,contentHeight-result.height),state.maxValue)
                state.dispatchRawDelta(state.maxValue.toFloat());assertEquals(state.maxValue,state.value)
            }
    }
}
