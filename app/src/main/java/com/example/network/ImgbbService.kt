package com.example.network

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Query
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

interface ImgbbService {
    @FormUrlEncoded
    @POST("1/upload")
    fun uploadImage(
        @Query("key") apiKey: String,
        @Field("image") base64Image: String
    ): Call<ImgbbResponse>

    companion object {
        private const val BASE_URL = "https://api.imgbb.com/"

        fun create(): ImgbbService {
            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

            return retrofit.create(ImgbbService::class.java)
        }
    }
}

data class ImgbbResponse(
    val data: ImgbbData?,
    val success: Boolean,
    val status: Int
)

data class ImgbbData(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val display_url: String = ""
)
