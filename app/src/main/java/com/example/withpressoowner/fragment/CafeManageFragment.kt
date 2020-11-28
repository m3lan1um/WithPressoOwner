package com.example.withpressoowner.fragment

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.TranslateAnimation
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.ViewGroupUtils
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.util.toHalf
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.withpressoowner.R
import com.example.withpressoowner.adapter.CafeImagePagerAdapter
import com.example.withpressoowner.service.*
import kotlinx.android.synthetic.main.cafe_whole_detail_info.*
import kotlinx.android.synthetic.main.cafe_whole_detail_info.view.*
import kotlinx.android.synthetic.main.fragment_cafe_manage.*
import kotlinx.android.synthetic.main.fragment_detail_info_sec.*
import kotlinx.android.synthetic.main.review_dialog_layout.*
import kotlinx.android.synthetic.main.review_dialog_layout.view.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.error
import org.jetbrains.anko.support.v4.alert
import org.jetbrains.anko.support.v4.toast
import org.jetbrains.anko.toast
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException
import java.net.HttpCookie.parse
import java.util.logging.Level.parse
import java.util.regex.Pattern

class CafeManageFragment : Fragment() {
    private lateinit var pref: SharedPreferences
    private lateinit var retrofit: Retrofit
    private lateinit var BASE_URL: String
    private lateinit var scope: CoroutineScope
    private lateinit var cafeInfo: CafeInfo
    private lateinit var wholeDetailInfo: View
    private lateinit var reviewDialog: View

    private var MODIFY_MODE = false
    private val OPEN_GALLERY = 1001
    private val GALLERY_ACCESS_REQUEST_CODE = 10001

    private var photoList: MutableList<String>? = null

    private var chair_back: Int? = null
    private var rest_in: Int? = null
    private var rest_gen_sep: Int? = null
    private var smoking_in: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_cafe_manage, container, false)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        /* init */
        pref = requireContext().getSharedPreferences("owner_info", 0)
        retrofit = Retrofit.Builder()
            .baseUrl("https://withpresso.gq")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        BASE_URL = "https://withpresso.gq"
        wholeDetailInfo = LayoutInflater.from(requireContext()).inflate(R.layout.cafe_whole_detail_info, null)
        reviewDialog = LayoutInflater.from(requireContext()).inflate(R.layout.review_dialog_layout, null)
        scope = CoroutineScope(Dispatchers.IO)
        info_cafe_photo_pager.orientation = ViewPager2.ORIENTATION_HORIZONTAL

        cafe_manage_review_button.setOnClickListener {
            reviewDialog.parent?.let { (reviewDialog.parent as ViewGroup).removeView(reviewDialog) }
            AlertDialog.Builder(requireContext())
                .setView(reviewDialog)
                .setNegativeButton("취소하기") { dialogInterface: DialogInterface, i: Int -> }
                .setPositiveButton("보내기") { dialogInterface: DialogInterface, i: Int ->
                    /* 입력 받은 전화번호 서버로 보내기 */
                    if (!phoneNumFormatCheck(reviewDialog.phone_number_edit.text.toString()))
                        toast("형식에 맞게 다시 입력해주세요")
                    else {
                        val phoneNum = reviewDialog.phone_number_edit.text.toString()
                        val sendAuthCodeService = retrofit.create(SendAuthCodeService::class.java)
                        sendAuthCodeService.requestSendMsg(phoneNum).enqueue(object :Callback<Int>{
                            override fun onFailure(call: Call<Int>, t: Throwable) {
                                Log.e("send msg request", "failed")
                                alert("인증 코드 발송을 실패했습니다.")
                            }

                            override fun onResponse(call: Call<Int>, response: Response<Int>) {
                                val result = response.body()
                                result?.let {
                                    if (result == 1)
                                        toast("인증 코드를 발송했습니다!")
                                    else
                                        toast("인증 코드 발송을 실패했습니다.")
                                }
                            }
                        })
                    }
                }
                .show()
        }

        /* 세부 정보 자세히 보기 */
        cafe_manage_refresh.setOnRefreshListener {
            onResume()
            cafe_manage_refresh.isRefreshing = false
        }
        cafe_manage_whole_detail_button.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setView(wholeDetailInfo)
                .show()
        }

        cafe_manage_select_photo_button.setOnClickListener { permissionCheckAndOpenGallery() }

        cafe_manage_modify_button.setOnClickListener {
            if (!MODIFY_MODE)
                openDetailInfoLayout()
            else
                closeDetailInfoLayout()
        }

        cafe_manage_logout_button.setOnClickListener {
            pref.edit().clear().apply()
            findNavController().navigate(R.id.action_cafeManageFragment_to_logInFragment)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onResume() {
        super.onResume()

        scope.launch(Dispatchers.Main) {
            val initCafeInfo = scope.launch {
                val cafeInfoService = retrofit.create(CafeInfoService::class.java)
                cafeInfo = cafeInfoService.requestCafeInfo(pref.getInt("cafe_asin", 0))
            }
            initCafeInfo.join()

            val initTextInfo = this.launch {
                toast("사진 개수: ${cafeInfo.num_of_pics}")

                /* 자세한 정보 초기화 */
                initWholeDetailInfo()

                /* 카페 이름, 카페 주소 */
                cafe_manage_name_text.text = cafeInfo.cafe_name
                cafe_manage_addr_text.text = cafeInfo.cafe_addr

                /* 기본 정보 */
                cafe_manage_cafe_hour_text.text = "운영시간: ${cafeInfo.cafe_hour}"
                cafe_manage_cafe_tel_text.text = "전화번호: ${cafeInfo.cafe_tel}"
                cafe_manage_cafe_menu_text.text = "메뉴: ${cafeInfo.cafe_menu}"

                /* 세부 정보 */
                cafe_manage_table_text.text =
                    "1인석/2인석/4인석/다인석: ${cafeInfo.table_struct}\n" +
                            "넓이(2인석 기준): " + "A4 ${cafeInfo.table_size}장"
                cafe_manage_chair_text.text =
                    "쿠션감: ${cafeInfo.chair_cushion}\n" +
                            "등받이: ${textConverter("chair_back", cafeInfo.chair_back)}"
                cafe_manage_plug_text.text =
                    "개수: ${cafeInfo.num_plug}"
                cafe_manage_congestion_text.text =
                    "혼잡도: 받아와서 보여주기"
            }

            val initDetailInfoAndPhotoList = scope.launch(Dispatchers.Default) {
                photoList = getPhotoList(
                        pref.getInt("cafe_asin", 0), cafeInfo.num_of_pics
                )
            }
            initDetailInfoAndPhotoList.join()

            val initPhotoList = this.launch {
                info_cafe_photo_pager.adapter = CafeImagePagerAdapter(requireContext(), photoList as ArrayList<String>?)
            }
        }
    }

    private fun textConverter(category: String, value: Int): String? {
        return when(category) {
            "chair_back" -> {
                if (value == 1) "있어요."
                else "없어요."
            }
            "rest_in" -> {
                if(value == 1) "매장 안에 있어요."
                else "매장 밖에 있어요."
            }
            "rest_gen_sep" -> {
                if(value == 1) "구분되어 있어요."
                else "구분되어 있지 않아요."
            }
            "smoking_room" -> {
                if(value == 1) "있어요."
                else "없어요."
            }
            else -> null
        }
    }

    private fun getPhotoList(cafe_asin: Int, size: Int): MutableList<String>? {
        if(size == 0) return null

        val photoList = MutableList(size) {
                index -> "${BASE_URL}/cafe_pics/${cafe_asin}/${index + 1}.jpg"
        }

        return photoList as ArrayList<String>
    }

    @SuppressLint("SetTextI18n")
    private fun initWholeDetailInfo() {
        wholeDetailInfo.info_detail_all_congestion_text.text =
            "혼잡도: 받아와서 보여주기"
        wholeDetailInfo.info_detail_all_table_text.text =
            "1인석/2인석/4인석/다인석: ${cafeInfo.table_struct}\n" +
                    "넓이(2인석 기준): " + "A4 ${cafeInfo.table_size}장"
        wholeDetailInfo.info_detail_all_chair_text.text =
            "쿠션감: ${cafeInfo.chair_cushion}\n" +
                    "등받이: ${textConverter("chair_back", cafeInfo.chair_back)}"
        wholeDetailInfo.info_detail_all_outlet_text.text =
            "개수: ${cafeInfo.num_plug}"
        wholeDetailInfo.info_detail_all_music_text.text =
            "장르: ${cafeInfo.music_genre}"
        wholeDetailInfo.info_detail_all_restroom_text.text =
            "위치: ${textConverter("rest_in", cafeInfo.rest_in)}\n" +
                    "성별 분리: ${textConverter("rest_gen_sep", cafeInfo.rest_gen_sep)}"
        wholeDetailInfo.info_detail_all_smoking_room_text.text =
            "흡연실: ${textConverter("smoking_room", cafeInfo.smoking_room)}"
        wholeDetailInfo.info_detail_all_anti_corona_text.text =
            "최근 방역 날짜: ${cafeInfo.anco_data}"
        wholeDetailInfo.info_detail_all_discount_text.text =
            "재주문 시 할인율: ${cafeInfo.discount}%"

        cafeInfo.cafe_clean = Math.round((cafeInfo.cafe_clean * 10)) / 10f
        cafeInfo.rest_clean = Math.round((cafeInfo.rest_clean * 10)) / 10f
        cafeInfo.noise = Math.round((cafeInfo.noise * 10)) / 10f
        cafeInfo.study_well = Math.round((cafeInfo.study_well * 10)) / 10f

        wholeDetailInfo.info_detail_all_cafe_clean_text.text =
            "매장 청결: ${cafeInfo.cafe_clean}점\n" +
                    "화장실 청결: ${cafeInfo.rest_clean}점"
        wholeDetailInfo.info_detail_all_noise_text.text =
            "주변 소리(1점 = 시끄러움 ~ 5점 = 조용): ${cafeInfo.noise}점"
        wholeDetailInfo.info_detail_all_study_well_text.text =
            "공부 잘 됨 지수: ${cafeInfo.study_well}점"
    }

    private fun setLayoutOnClickListener(parentLayout: LinearLayout, childLayout: LinearLayout, arrow: ImageView) {
        parentLayout.setOnClickListener {
            val currDegree = arrow.rotation
            if (childLayout.visibility == View.GONE) {
                /* 변경 항목 보여주기 */
                childLayout.visibility = View.VISIBLE
                val animator = AlphaAnimation(0f, 1f)
                animator.duration = 100
                childLayout.animation = animator
                /* 버튼 회전 */
                ObjectAnimator.ofFloat(arrow, View.ROTATION, currDegree, currDegree + 90f)
                    .setDuration(300)
                    .start()
            }
            else {
                /* 변경 항목 보여주기 */
                val animator = AlphaAnimation(1f, 0f)
                animator.duration = 100
                childLayout.animation = animator
                childLayout.visibility = View.GONE
                /* 버튼 회전 */
                ObjectAnimator.ofFloat(arrow, View.ROTATION, currDegree, currDegree - 90f)
                    .setDuration(300)
                    .start()
            }
        }
    }

    /* 갤러리 접근 권한 */
    private fun permissionCheckAndOpenGallery() {
        val permission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE
        )

        /* 이미 갤러리 접근을 허용 했음 */
        if(permission == PermissionChecker.PERMISSION_GRANTED){
            openGallery()
        }
        /* 갤러리 접근이 허용된 적 없음 */
        else {
            val isAccessDenied = ActivityCompat.shouldShowRequestPermissionRationale(
                requireActivity(), Manifest.permission.READ_EXTERNAL_STORAGE
            )

            /* 최초로 갤러리에 접근 시도 */
            if(!isAccessDenied) {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    GALLERY_ACCESS_REQUEST_CODE
                )
            }
            /* 갤러리 접근 권한 제한됨 */
            else {
                val dialog = AlertDialog.Builder(requireContext())
                dialog.setTitle("갤러리 접근 권한 제한됨")
                dialog.setMessage("  갤러리 접근을 허용해 주세요.\n\n" +
                        "  app info -> Permissions -> storage")
                dialog.show()
            }
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when(requestCode) {
            /* 갤러리에 접근이 허용되면 갤러리 열기 */
            GALLERY_ACCESS_REQUEST_CODE -> {
                if(grantResults.isNotEmpty() &&
                    grantResults[0] == PermissionChecker.PERMISSION_GRANTED) {
                    openGallery()
                }
            }
        }

        return
    }

    /* 갤러리 열어서 사진 선택 */
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, OPEN_GALLERY)
    }

    /*
    * 1. pref: 선택한 사진 파일 이름을 "profile"의 value로 저장
    * 2. glide: profile 이미지 다시 그려주기. 이때 diskCache는 RESOURCE로 설정
    * 3. retrofit: 이미지 서버로 보내기
    * */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if(requestCode == OPEN_GALLERY && resultCode == Activity.RESULT_OK) {
            openDetailInfoLayout()

            var num_of_pics = 0
            val partList = mutableListOf<MultipartBody.Part>()

            data?.let {
                val clipData = data.clipData
                clipData?.let {
                    toast("# of selected photo: ${clipData.itemCount}")
                    num_of_pics = clipData.itemCount
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri
                        val absPath = absolutelyPath(uri)
                        val imageFile = File(absPath)
                        val reqBody = RequestBody.create(
                            MediaType.parse("multipart/form-data"), imageFile
                        )
                        val partBody = MultipartBody.Part.createFormData(
                            "cafe_photo", imageFile.name, reqBody
                        )
                        partList.add(partBody)
                    }
                }

                /* 통신 시도 */
                val addCafePhotoService = retrofit.create(AddCafePhotoService::class.java)
                addCafePhotoService.requestAddPhoto(
                    pref.getInt("cafe_asin", 0),
                    num_of_pics,
                    partList as ArrayList<MultipartBody.Part>
                ).enqueue(object :Callback<Int> {
                    /* 통신 실패 */
                    override fun onFailure(call: Call<Int>, t: Throwable) {
                        Log.e("add cafe photo", "failed")
                    }

                    /* 통신 성공 */
                    override fun onResponse(call: Call<Int>, response: Response<Int>) {
                        val total_num_of_pics = response.body()
                        total_num_of_pics?.let {
                            toast("통신 성공!")
                            toast("변경된 사진의 수: ${total_num_of_pics}")
                            scope.launch(Dispatchers.Default) {
                                val updatephotoList = this.launch {
                                    photoList = getPhotoList(
                                        pref.getInt("cafe_asin", 0), total_num_of_pics
                                    )
                                }
                                updatephotoList.join()

                                this.launch(Dispatchers.Main) {
                                    // closeDetailInfoLayout()
                                    info_cafe_photo_pager.adapter = CafeImagePagerAdapter(requireContext(), photoList as ArrayList<String>)
                                }
                            }
                        }
                    }
                })
            }
        }
        else {
            Log.d("ActivityResult", "1. not select new image 2. failed to load new image")
        }
    }

    /* 전달된 file uri를 절대 경로로 바꿔주는 함수 */
    private fun absolutelyPath(path: Uri): String {
        var proj: Array<String> = arrayOf(MediaStore.Images.Media.DATA)
        var c = requireContext().contentResolver.query(path, proj, null, null, null)!!
        var index = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        c.moveToFirst()

        return c.getString(index)
    }

    private fun setOnHideKeypadListener(view: View) {
        view.setOnFocusChangeListener { v, hasFocus ->
            if(!hasFocus) {
                val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }

    private fun openDetailInfoLayout() {
        /*
        * 1. 수정 모드로 변경
        * 2. 기존에 보이던 정보 layout 숨기기
        * 3. inflate한 layout 그리기
        * 4. onClickListener 달기
        * 5. 버튼 글씨 바꾸기
        * */
        MODIFY_MODE = true

        wholeDetailInfo.parent?.let {
            (wholeDetailInfo.parent as ViewGroup).removeView(wholeDetailInfo)
        }
        cafe_manage_detail_info_layout.addView(wholeDetailInfo, 1)

        setLayoutOnClickListener(whole_detail_all_congestion_layout, whole_detail_congestion_layout, whole_detail_congestion_arrow_image)
        setLayoutOnClickListener(whole_detail_all_table_layout, whole_detail_table_layout, whole_detail_table_arrow_image)
        setLayoutOnClickListener(info_detail_all_chair_layout, whole_detail_chair_layout, whole_detail_chair_arrow_image)
        setLayoutOnClickListener(info_detail_all_outlet_layout, whole_detail_num_outlet_layout, whole_detail_outlet_arrow_image)
        setLayoutOnClickListener(info_detail_all_music_layout, whole_detail_music_layout, whole_detail_music_arrow_imgae)
        setLayoutOnClickListener(info_detail_all_restroom_layout, whole_detail_rest_layout, whole_detail_rest_arrow_image)
        setLayoutOnClickListener(info_detail_all_smoking_room_layout, whole_detail_smoking_room_layout, whole_detail_smoking_arrow_image)
        setLayoutOnClickListener(info_detail_all_anti_corona_layout, whole_detail_anti_corona_layout, whole_detail_anti_arrow_image)
        setLayoutOnClickListener(info_detail_all_discount_layout, whole_detail_discount_layout, whole_detail_discount_arrow_image)
        setOnHideKeypadListener(whole_detail_congestion_edit)
        setOnHideKeypadListener(whole_detail_seat1_edit)
        setOnHideKeypadListener(whole_detail_seat2_edit)
        setOnHideKeypadListener(whole_detail_seat4_edit)
        setOnHideKeypadListener(whole_detail_multi_seat_edit)
        setOnHideKeypadListener(whole_detail_table_size_edit)
        setOnHideKeypadListener(whole_detail_chair_cushion_edit)
        setOnHideKeypadListener(whole_detail_num_outlet_edit)
        setOnHideKeypadListener(whole_detail_genre_edit)
        setOnHideKeypadListener(whole_detail_anti_corona_edit)
        setOnHideKeypadListener(whole_detail_discount_edit)
        whole_detail_chair_back_group.setOnCheckedChangeListener { group, checkedId ->
            when(checkedId) {
                R.id.whole_detail_chair_back_yes_radio -> chair_back = 1
                R.id.whole_detail_chair_back_no_radio -> chair_back = 0
            }
        }
        whole_detail_rest_loc_group.setOnCheckedChangeListener { group, checkedId ->
            when(checkedId) {
                R.id.whole_detail_rest_in_radio -> rest_in = 1
                R.id.whole_detail_rest_out_radio -> rest_in = 0
            }
        }
        whole_detail_rest_gen_sep_group.setOnCheckedChangeListener { group, checkedId ->
            when(checkedId) {
                R.id.whole_detail_gen_sep_yes_radio -> rest_gen_sep = 1
                R.id.whole_detail_gen_sep_no_radio -> rest_gen_sep = 0
            }
        }
        whole_detail_smoking_room_group.setOnCheckedChangeListener { group, checkedId ->
            when(checkedId) {
                R.id.detail_info2_smoking_room_in_radio -> smoking_in = 1
                R.id.detail_info2_smoking_room_out_radio -> smoking_in = 0
            }
        }

        /* 사진 추가하기 버튼 X */
        cafe_manage_select_photo_button.visibility = View.VISIBLE
        /* 세부 정보 버튼&정보 X*/
        cafe_manage_review_button.visibility = View.GONE
        cafe_manage_whole_detail_button.visibility = View.GONE
        cafe_manage_detail_info_list.visibility = View.GONE
        /* 화살표 O */
        whole_detail_congestion_arrow_image.visibility = View.VISIBLE
        whole_detail_table_arrow_image.visibility = View.VISIBLE
        whole_detail_chair_arrow_image.visibility = View.VISIBLE
        whole_detail_outlet_arrow_image.visibility = View.VISIBLE
        whole_detail_music_arrow_imgae.visibility = View.VISIBLE
        whole_detail_rest_arrow_image.visibility = View.VISIBLE
        whole_detail_smoking_arrow_image.visibility = View.VISIBLE
        whole_detail_anti_arrow_image.visibility = View.VISIBLE
        whole_detail_discount_arrow_image.visibility = View.VISIBLE

        cafe_manage_modify_button.text = getString(R.string.change_reflect)
    }

    private fun closeDetailInfoLayout() {
        /*
                * 1. 참조 모드로 변경
                * 2. 서버로 데이터 전송(병렬)
                * 3. 수정할 수 있는 layout 숨기기
                * 4. 기존에 정보 보여주는 layout 그리기
                * 5. 버튼 글씨 바꾸기
                * */
        MODIFY_MODE = false

        /* 카페 기본 정보 */
        val cafe_name = cafeInfo.cafe_name
        val cafe_hour = cafeInfo.cafe_hour
        val cafe_addr = cafeInfo.cafe_addr
        val cafe_tel = cafeInfo.cafe_tel
        val cafe_menu = cafeInfo.cafe_menu
        /* 카페 세부 정보 */
        /* 1.책상 */
        val table_info_array = cafeInfo.table_struct.split("/") as ArrayList
        if (whole_detail_seat1_edit.text.isNotEmpty())
            table_info_array[0] = whole_detail_seat1_edit.text.toString()
        if (whole_detail_seat2_edit.text.isNotEmpty())
            table_info_array[1] = whole_detail_seat2_edit.text.toString()
        if (whole_detail_seat4_edit.text.isNotEmpty())
            table_info_array[2] = whole_detail_seat4_edit.text.toString()
        if (whole_detail_multi_seat_edit.text.isNotEmpty())
            table_info_array[3] = whole_detail_multi_seat_edit.text.toString()
        val table_info = "${table_info_array[0]}/${table_info_array[1]}/${table_info_array[2]}/${table_info_array[3]}"
        val table_size_info = whole_detail_table_size_edit.text.toString().toIntOrNull() ?: cafeInfo.table_size
        /* 2.의자 */
        val chair_back_info = chair_back ?: cafeInfo.chair_back
        var chair_cushion_info = whole_detail_chair_cushion_edit.text.toString()
        if (chair_cushion_info.isBlank()) chair_cushion_info = cafeInfo.chair_cushion
        /* 3.플러그 */
        val num_plug = whole_detail_num_outlet_edit.text.toString().toIntOrNull() ?: cafeInfo.num_plug
        /* 4.음악 */
        var bgm_info = whole_detail_genre_edit.text.toString()
        if (bgm_info.isBlank())   bgm_info = cafeInfo.music_genre
        /* 5.화장실 정보 */
        val toilet_info = rest_in ?: cafeInfo.rest_in
        val toilet_gender_info = rest_gen_sep ?: cafeInfo.rest_gen_sep
        /* 6.방역 날짜 */
        var sterilization_info = whole_detail_anti_corona_edit.text.toString()
        if (sterilization_info.isBlank()) sterilization_info = cafeInfo.anco_data
        /* 7.흡연실 유무 */
        val smoking_room = smoking_in ?: cafeInfo.smoking_room
        /* 11.할인율 */
        val discount = whole_detail_discount_edit.text.toString().toIntOrNull() ?: cafeInfo.discount
        val cafeInfoUpdateService = retrofit.create(CafeInfoUpdateService::class.java)

        scope.launch {
            val result = cafeInfoUpdateService.requestCafeInfoUpdate(
                pref.getInt("cafe_asin", 0),
                cafe_name, cafe_addr, cafe_hour, cafe_tel, cafe_menu,
                table_info, table_size_info,
                chair_back_info, chair_cushion_info,
                num_plug,
                bgm_info,
                toilet_info, toilet_gender_info,
                sterilization_info,
                smoking_room,
                discount
            )
            if (result == 1) {
                val resetDetailinfo = this.launch(Dispatchers.Main) {
                    /* 세부 정보 레이아웃 X */
                    whole_detail_congestion_layout.visibility = View.GONE
                    whole_detail_table_layout.visibility = View.GONE
                    whole_detail_chair_layout.visibility = View.GONE
                    whole_detail_num_outlet_layout.visibility = View.GONE
                    whole_detail_music_layout.visibility = View.GONE
                    whole_detail_rest_layout.visibility = View.GONE
                    whole_detail_smoking_room_layout.visibility = View.GONE
                    whole_detail_anti_corona_layout.visibility = View.GONE
                    whole_detail_discount_layout.visibility = View.GONE
                    /* 사진 추가하기 버튼 X */
                    cafe_manage_select_photo_button.visibility = View.GONE
                    /* 화살표 X */
                    whole_detail_congestion_arrow_image.visibility = View.GONE
                    whole_detail_table_arrow_image.visibility = View.GONE
                    whole_detail_chair_arrow_image.visibility = View.GONE
                    whole_detail_outlet_arrow_image.visibility = View.GONE
                    whole_detail_music_arrow_imgae.visibility = View.GONE
                    whole_detail_rest_arrow_image.visibility = View.GONE
                    whole_detail_smoking_arrow_image.visibility = View.GONE
                    whole_detail_anti_arrow_image.visibility = View.GONE
                    whole_detail_discount_arrow_image.visibility = View.GONE
                    /* 세부 정보 & 수정 버튼 O*/
                    cafe_manage_review_button.visibility = View.VISIBLE
                    cafe_manage_whole_detail_button.visibility = View.VISIBLE
                    cafe_manage_detail_info_list.visibility = View.VISIBLE
                    /* 버튼 글씨 바꾸기 */
                    cafe_manage_modify_button.text = getString(R.string.modify)

                    this.launch {
                        wholeDetailInfo.parent?.let {
                            (wholeDetailInfo.parent as ViewGroup).removeView(wholeDetailInfo)
                        }
                    }.join()

                    onResume()
                }
            }
            else
                toast("업데이트 실패")
        }
    }

    private fun phoneNumFormatCheck(phone: String): Boolean {
        return if(phone.length != 11) false
        else Pattern.matches("^010[0-9]*\$", phone)
    }

}