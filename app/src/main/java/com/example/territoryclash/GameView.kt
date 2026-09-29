package com.example.territoryclash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class GameView(context: Context) : View(context) {
    data class Cell(var owner: Int = -1, var troops: Float = 1f)

    private val cols = 24
    private val rows = 14
    private val factions = 5
    private val cells = Array(rows) { Array(cols) { Cell() } }
    private val colors = intArrayOf(
        Color.rgb(50,145,255), Color.rgb(235,70,85), Color.rgb(255,165,50),
        Color.rgb(170,90,235), Color.rgb(70,205,130)
    )
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private var selectedX = -1
    private var selectedY = -1
    private var cursorX = 3
    private var cursorY = rows / 2
    private var attackPercent = .5f
    private var last = System.nanoTime()
    private var botClock = 0f
    private var gameOver: String? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        reset()
    }

    private fun reset() {
        for (y in 0 until rows) for (x in 0 until cols) {
            cells[y][x].owner = -1
            cells[y][x].troops = 1f + Random.nextFloat() * 3f
        }
        val starts = listOf(3 to rows/2, cols-4 to rows/2, cols/2 to 2, cols/2 to rows-3, cols/2 to rows/2)
        starts.forEachIndexed { id, (sx, sy) ->
            for (dy in -1..1) for (dx in -1..1) {
                val x = (sx+dx).coerceIn(0, cols-1)
                val y = (sy+dy).coerceIn(0, rows-1)
                cells[y][x].owner = id
                cells[y][x].troops = if (dx == 0 && dy == 0) 20f else 8f
            }
        }
        selectedX = -1
        selectedY = -1
        cursorX = 3
        cursorY = rows/2
        gameOver = null
        last = System.nanoTime()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = ((now-last)/1_000_000_000f).coerceIn(0f,.05f)
        last = now
        update(dt)
        drawWorld(canvas)
        postInvalidateOnAnimation()
    }

    private fun update(dt: Float) {
        if (gameOver != null) return
        for (row in cells) for (c in row) if (c.owner >= 0) c.troops = min(99f, c.troops + dt*.5f)
        botClock += dt
        if (botClock > .7f) {
            botClock = 0f
            for (id in 1 until factions) botTurn(id)
            checkEnd()
        }
    }

    private fun botTurn(id: Int) {
        val src = mutableListOf<Pair<Int,Int>>()
        for (y in 0 until rows) for (x in 0 until cols)
            if (cells[y][x].owner == id && cells[y][x].troops > 7f && neighbors(x,y).any { cells[it.second][it.first].owner != id })
                src += x to y
        if (src.isEmpty()) return
        val (sx,sy) = src.random()
        val targets = neighbors(sx,sy).filter { cells[it.second][it.first].owner != id }
        if (targets.isEmpty()) return
        val (tx,ty) = targets.minByOrNull { cells[it.second][it.first].troops } ?: return
        move(sx,sy,tx,ty,id,.55f)
    }

    private fun drawWorld(c: Canvas) {
        c.drawColor(Color.rgb(15,20,28))
        val top = 70f
        val bottom = height - 90f
        val cw = width / cols.toFloat()
        val ch = (bottom-top) / rows.toFloat()

        for (y in 0 until rows) for (x in 0 until cols) {
            val cell = cells[y][x]
            p.style = Paint.Style.FILL
            p.color = if (cell.owner < 0) Color.rgb(50,56,66) else colors[cell.owner]
            c.drawRect(x*cw+1, top+y*ch+1, (x+1)*cw-1, top+(y+1)*ch-1, p)
            if (cw > 28f) {
                text.textSize = min(cw,ch)*.3f
                c.drawText(cell.troops.toInt().toString(), x*cw+cw/2, top+y*ch+ch*.62f, text)
            }
        }

        fun outline(x:Int,y:Int,color:Int,stroke:Float) {
            if (x < 0 || y < 0) return
            p.style = Paint.Style.STROKE
            p.strokeWidth = stroke
            p.color = color
            c.drawRect(x*cw+3, top+y*ch+3, (x+1)*cw-3, top+(y+1)*ch-3, p)
        }
        outline(selectedX,selectedY,Color.WHITE,5f)
        outline(cursorX,cursorY,Color.YELLOW,3f)

        text.textAlign = Paint.Align.LEFT
        text.textSize = 27f
        c.drawText("Territory Clash   Земля: ${land(0)}   Армия: ${army(0).toInt()}", 18f, 32f, text)
        text.textSize = 18f
        c.drawText("Тап/мышь: своя клетка → соседняя | WASD/стрелки + Space | +/- сила | R рестарт",18f,57f,text)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 24f
        c.drawText("Атака: ${(attackPercent*100).toInt()}%", width/2f, height-35f, text)
        gameOver?.let {
            p.style = Paint.Style.FILL
            p.color = Color.argb(210,0,0,0)
            c.drawRect(0f,top,width.toFloat(),bottom,p)
            text.textSize = 36f
            c.drawText(it + " — R для рестарта",width/2f,(top+bottom)/2f,text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        requestFocus()
        if (e.action != MotionEvent.ACTION_DOWN) return true
        val top = 70f
        val bottom = height - 90f
        if (e.y !in top..bottom) {
            if (e.y > bottom) attackPercent = if (e.x < width/2) max(.1f,attackPercent-.1f) else min(.9f,attackPercent+.1f)
            return true
        }
        val x = (e.x / (width/cols.toFloat())).toInt().coerceIn(0,cols-1)
        val y = ((e.y-top) / ((bottom-top)/rows)).toInt().coerceIn(0,rows-1)
        cursorX=x; cursorY=y; activate(x,y)
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL) {
            attackPercent = (attackPercent + if (e.getAxisValue(MotionEvent.AXIS_VSCROLL)>0) .05f else -.05f).coerceIn(.1f,.9f)
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        when(code) {
            KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_DPAD_LEFT -> cursorX=max(0,cursorX-1)
            KeyEvent.KEYCODE_D,KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX=min(cols-1,cursorX+1)
            KeyEvent.KEYCODE_W,KeyEvent.KEYCODE_DPAD_UP -> cursorY=max(0,cursorY-1)
            KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_DPAD_DOWN -> cursorY=min(rows-1,cursorY+1)
            KeyEvent.KEYCODE_SPACE,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_CENTER -> activate(cursorX,cursorY)
            KeyEvent.KEYCODE_PLUS,KeyEvent.KEYCODE_EQUALS,KeyEvent.KEYCODE_NUMPAD_ADD -> attackPercent=min(.9f,attackPercent+.1f)
            KeyEvent.KEYCODE_MINUS,KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> attackPercent=max(.1f,attackPercent-.1f)
            KeyEvent.KEYCODE_R -> reset()
            else -> return super.onKeyDown(code,e)
        }
        return true
    }

    private fun activate(x:Int,y:Int) {
        if (gameOver != null) return
        if (selectedX < 0) {
            if (cells[y][x].owner == 0) { selectedX=x; selectedY=y }
            return
        }
        if (x==selectedX && y==selectedY) { selectedX=-1; selectedY=-1; return }
        if (adj(selectedX,selectedY,x,y)) {
            move(selectedX,selectedY,x,y,0,attackPercent)
            if (cells[y][x].owner==0) { selectedX=x; selectedY=y }
            checkEnd()
        } else if (cells[y][x].owner==0) { selectedX=x; selectedY=y }
    }

    private fun move(sx:Int,sy:Int,tx:Int,ty:Int,id:Int,pct:Float) {
        if (!adj(sx,sy,tx,ty)) return
        val s=cells[sy][sx]; val t=cells[ty][tx]
        if (s.owner != id) return
        val amount=min(max(0f,s.troops-1f), max(1f,s.troops*pct))
        if (amount<=0) return
        s.troops-=amount
        if (t.owner==id) t.troops=min(99f,t.troops+amount)
        else if (amount>t.troops) { t.owner=id; t.troops=max(1f,amount-t.troops) }
        else t.troops-=amount
    }

    private fun adj(a:Int,b:Int,x:Int,y:Int)=abs(a-x)+abs(b-y)==1

    private fun neighbors(x:Int,y:Int):List<Pair<Int,Int>> = buildList {
        if(x>0)add(x-1 to y); if(x<cols-1)add(x+1 to y); if(y>0)add(x to y-1); if(y<rows-1)add(x to y+1)
    }

    private fun land(id:Int)=cells.sumOf { row -> row.count { it.owner==id } }
    private fun army(id:Int):Float {
        var total=0f
        for(row in cells) for(cell in row) if(cell.owner==id) total+=cell.troops
        return total
    }

    private fun checkEnd() {
        var player=false
        val enemy=BooleanArray(factions)
        for(row in cells) for(cell in row) if(cell.owner>=0) {
            if(cell.owner==0) player=true else enemy[cell.owner]=true
        }
        if(!player) gameOver="Поражение"
        else if((1 until factions).none { enemy[it] }) gameOver="Победа!"
    }
}
