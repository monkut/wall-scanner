package io.github.monkut.wallscanner.scan

import android.opengl.GLES20
import android.opengl.Matrix
import io.github.monkut.wallscanner.core.VoxelCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Draws the accumulated voxel cloud as colored GL points over the camera image. */
class PointsRenderer {
    private var program = 0
    private var vbo = 0
    private var count = 0
    private var mvpUniform = 0
    private var pointSizeUniform = 0
    private var positionAttrib = 0
    private var colorAttrib = 0
    private val mvp = FloatArray(16)
    private var buffer: FloatBuffer? = null

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        mvpUniform = GLES20.glGetUniformLocation(program, "u_Mvp")
        pointSizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        colorAttrib = GLES20.glGetAttribLocation(program, "a_Color")
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
    }

    /** Uploads a new cloud. Must be called on the GL thread. */
    fun update(cloud: VoxelCloud) {
        val n = cloud.count
        val floats = n * FLOATS_PER_POINT
        var buf = buffer
        if (buf == null || buf.capacity() < floats) {
            buf = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer = buf
        }
        buf.clear()
        for (i in 0 until n) {
            buf.put(cloud.xyz[i * 3]).put(cloud.xyz[i * 3 + 1]).put(cloud.xyz[i * 3 + 2])
            buf.put((cloud.rgb[i * 3].toInt() and 0xff) / 255f)
            buf.put((cloud.rgb[i * 3 + 1].toInt() and 0xff) / 255f)
            buf.put((cloud.rgb[i * 3 + 2].toInt() and 0xff) / 255f)
        }
        buf.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floats * 4, buf, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        count = n
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (count == 0) return
        Matrix.multiplyMM(mvp, 0, projectionMatrix, 0, viewMatrix, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)
        GLES20.glUniform1f(pointSizeUniform, POINT_SIZE_PX)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, 0)
        GLES20.glEnableVertexAttribArray(colorAttrib)
        GLES20.glVertexAttribPointer(colorAttrib, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, 12)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(colorAttrib)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        private const val FLOATS_PER_POINT = 6
        private const val STRIDE_BYTES = FLOATS_PER_POINT * 4
        private const val POINT_SIZE_PX = 6f

        private const val VERTEX_SHADER = """
            uniform mat4 u_Mvp;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            attribute vec3 a_Color;
            varying vec3 v_Color;
            void main() {
                gl_Position = u_Mvp * a_Position;
                gl_PointSize = u_PointSize;
                v_Color = a_Color;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 v_Color;
            void main() {
                gl_FragColor = vec4(v_Color, 1.0);
            }
        """
    }
}
