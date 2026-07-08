package com.example.ui

import android.Manifest
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.model.CallSession
import com.example.model.FirebaseConfig
import com.example.model.FriendRequest
import com.example.model.Message
import com.example.model.User
import com.example.network.AgoraManager
import com.example.ui.theme.*
import com.example.viewmodel.ChatViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ChatAppMainUI(viewModel: ChatViewModel) {
    val currentUser by viewModel.currentUser.collectAsState()
    val currentCall by viewModel.currentCall.collectAsState()
    val firebaseReady by viewModel.firebaseReady.collectAsState()

    var currentScreen by remember { mutableStateOf("login") }
    var selectedFriendForChat by remember { mutableStateOf<User?>(null) }

    // Navigation trigger based on auth state
    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            currentScreen = "dashboard"
        } else {
            currentScreen = "login"
        }
    }

    // Permission state for Agora Call
    val callPermissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    Box(modifier = Modifier.fillMaxSize().background(DeepSlate)) {
        // Main Screen Navigation
        when (currentScreen) {
            "login" -> LoginScreen(
                viewModel = viewModel,
                onNavigateToRegister = { currentScreen = "register" }
            )
            "register" -> RegisterScreen(viewModel, onNavigateToLogin = { currentScreen = "login" })
            "dashboard" -> DashboardScreen(
                viewModel = viewModel,
                onFriendSelected = { friend ->
                    selectedFriendForChat = friend
                    viewModel.selectChatUser(friend.uid)
                    currentScreen = "chat"
                }
            )
            "chat" -> {
                selectedFriendForChat?.let { friend ->
                    ChatRoomScreen(
                        viewModel = viewModel,
                        friend = friend,
                        onBack = {
                            currentScreen = "dashboard"
                            selectedFriendForChat = null
                        },
                        onRequestPermissions = {
                            callPermissionsState.launchMultiplePermissionRequest()
                        },
                        hasPermissions = callPermissionsState.allPermissionsGranted
                    )
                }
            }
        }

        // Global Call Overlay or Floating Banner
        currentCall?.let { call ->
            val isIncomingRinging = currentUser?.uid == call.receiverId && call.status == "ringing"
            if (isIncomingRinging) {
                FloatingCallBanner(
                    call = call,
                    onAccept = {
                        if (callPermissionsState.allPermissionsGranted) {
                            viewModel.acceptIncomingCall(call)
                        } else {
                            callPermissionsState.launchMultiplePermissionRequest()
                        }
                    },
                    onDecline = { viewModel.endCall() },
                    onClick = {
                        if (callPermissionsState.allPermissionsGranted) {
                            viewModel.acceptIncomingCall(call)
                        } else {
                            callPermissionsState.launchMultiplePermissionRequest()
                        }
                    }
                )
            } else {
                CallOverlay(
                    call = call,
                    currentUser = currentUser,
                    viewModel = viewModel,
                    onAccept = {
                        if (callPermissionsState.allPermissionsGranted) {
                            viewModel.acceptIncomingCall(call)
                        } else {
                            callPermissionsState.launchMultiplePermissionRequest()
                        }
                    },
                    onReject = { viewModel.endCall() },
                    onEnd = { viewModel.endCall() }
                )
            }
        }
    }
}

// --- Login Screen ---
@Composable
fun LoginScreen(
    viewModel: ChatViewModel,
    onNavigateToRegister: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isForgotPassword by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // App Identity
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(PrimaryNeon, SecondaryIndigo))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = "App Logo",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isForgotPassword) "Password Recovery" else "Connect Chat App",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Text(
                text = if (isForgotPassword) "Enter your registered email address" else "Stay connected with your friends in real-time.",
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )

            if (viewModel.isUsingSimulatedMode()) {
                Surface(
                    color = BrightRose.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "Alert", tint = BrightRose, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Firebase is not configured. Demo mode is active. Go to Settings to configure real Firebase services.",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            errorMsg?.let {
                Text(
                    text = it,
                    color = BrightRose,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                    textAlign = TextAlign.Center
                )
            }

             // Input Fields
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("email_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = BorderSlate,
                    focusedLabelColor = PrimaryNeon,
                    unfocusedLabelColor = TextSecondary
                )
            )

            if (!isForgotPassword) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("password_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryNeon,
                        unfocusedBorderColor = BorderSlate,
                        focusedLabelColor = PrimaryNeon,
                        unfocusedLabelColor = TextSecondary
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isLoading) {
                CircularProgressIndicator(color = PrimaryNeon)
            } else {
                Button(
                    onClick = {
                        if (email.isEmpty()) {
                            errorMsg = "Please enter your email."
                            return@Button
                        }
                        isLoading = true
                        errorMsg = null
                        if (isForgotPassword) {
                            viewModel.forgotPassword(email, {
                                isLoading = false
                                errorMsg = "Password reset link sent to your email."
                                isForgotPassword = false
                            }, {
                                isLoading = false
                                errorMsg = it
                            })
                        } else {
                            if (password.isEmpty()) {
                                errorMsg = "Please enter your password."
                                isLoading = false
                                return@Button
                            }
                            viewModel.login(email, password, {
                                isLoading = false
                            }, {
                                isLoading = false
                                errorMsg = it
                            })
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("login_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isForgotPassword) "Send Reset Link" else "Log In",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isForgotPassword) "Back to Login" else "Forgot Password?",
                        color = PrimaryNeon,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { isForgotPassword = !isForgotPassword }
                    )

                    Text(
                        text = "Create New Account",
                        color = SecondaryIndigo,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { onNavigateToRegister() }
                    )
                }
            }
        }
    }
}

// --- Register Screen ---
@Composable
fun RegisterScreen(viewModel: ChatViewModel, onNavigateToLogin: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Imgbb Avatar Selection
    val context = LocalContext.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.uploadImageToImgbb(it, "be818775a9cf13f8fd5b46cc805b2179") { url ->
                avatarUrl = url
            }
        }
    }

    val isUploadingImage by viewModel.isUploadingImage.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Create New Account",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Avatar Selector
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(SurfaceSlate)
                    .border(2.dp, PrimaryNeon, CircleShape)
                    .clickable { imagePickerLauncher.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (avatarUrl.isNotEmpty()) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = "Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (isUploadingImage) {
                    CircularProgressIndicator(color = PrimaryNeon, modifier = Modifier.size(24.dp))
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = "Add", tint = TextSecondary)
                        Text("Add Photo", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            errorMsg?.let {
                Text(
                    text = it,
                    color = BrightRose,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                    textAlign = TextAlign.Center
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your Full Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = BorderSlate,
                    focusedLabelColor = PrimaryNeon
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = BorderSlate,
                    focusedLabelColor = PrimaryNeon
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (at least 6 characters)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = BorderSlate,
                    focusedLabelColor = PrimaryNeon
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isLoading) {
                CircularProgressIndicator(color = PrimaryNeon)
            } else {
                Button(
                    onClick = {
                        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
                            errorMsg = "Please fill in all details."
                            return@Button
                        }
                        if (password.length < 6) {
                            errorMsg = "Password must be at least 6 characters long."
                            return@Button
                        }
                        isLoading = true
                        errorMsg = null
                        viewModel.register(name, email, password, avatarUrl, {
                            isLoading = false
                        }, {
                            isLoading = false
                            errorMsg = it
                        })
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Register Account", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Already have an account? Log In",
                    color = PrimaryNeon,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onNavigateToLogin() }
                )
            }
        }
    }
}

// --- Dashboard Screen ---
@Composable
fun DashboardScreen(
    viewModel: ChatViewModel,
    onFriendSelected: (User) -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val friendRequests by viewModel.friendRequests.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchResults.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Chats, 1: Requests, 2: Search

    var showEditProfileDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceSlate)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = currentUser?.avatarUrl ?: "https://i.ibb.co.com/gJZVWhG/avatar1.png",
                            contentDescription = "My Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BorderSlate)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = currentUser?.name ?: "User",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AccentGreen))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "Active Now", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showEditProfileDialog = true }) {
                            Icon(Icons.Default.ManageAccounts, contentDescription = "Edit Profile", tint = PrimaryNeon)
                        }
                        IconButton(onClick = { viewModel.logout() }) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "Logout", tint = BrightRose)
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceSlate,
                contentColor = TextPrimary,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.ChatBubble, contentDescription = "Chats") },
                    label = { Text("Chats") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = PrimaryNeon,
                        selectedTextColor = PrimaryNeon,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = BorderSlate
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Box {
                            Icon(Icons.Default.GroupAdd, contentDescription = "Requests")
                            if (friendRequests.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(BrightRose)
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                    },
                    label = { Text("Requests") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = PrimaryNeon,
                        selectedTextColor = PrimaryNeon,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = BorderSlate
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    label = { Text("Search") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = PrimaryNeon,
                        selectedTextColor = PrimaryNeon,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = BorderSlate
                    )
                )
            }
        },
        containerColor = DeepSlate
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> {
                    // Chat/Friends List Tab
                    if (friends.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.ChatBubbleOutline,
                            title = "No active conversations",
                            desc = "Search for users or friends to start a chat."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            items(friends) { friend ->
                                FriendItem(friend = friend, onClick = { onFriendSelected(friend) })
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                }
                1 -> {
                    // Requests Tab
                    if (friendRequests.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.Group,
                            title = "No pending requests",
                            desc = "Incoming friend requests will show up here."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            items(friendRequests) { request ->
                                FriendRequestItem(
                                    request = request,
                                    onAccept = { viewModel.acceptFriendRequest(request) },
                                    onReject = { viewModel.rejectFriendRequest(request) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                }
                2 -> {
                    // Search Tab
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                viewModel.searchUsers(it)
                            },
                            placeholder = { Text("Search by name, email or UID...") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "SearchIcon") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryNeon,
                                unfocusedBorderColor = BorderSlate
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (searchResults.isEmpty()) {
                            if (searchQuery.isNotEmpty()) {
                                Text("No user found.", color = TextSecondary, fontSize = 14.sp)
                            } else {
                                Text("Type a name or email address to discover new people.", color = TextSecondary, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn {
                                items(searchResults) { user ->
                                    var requestSent by remember { mutableStateOf(false) }
                                    SearchUserItem(
                                        user = user,
                                        requestSent = requestSent,
                                        onSendRequest = {
                                            viewModel.sendFriendRequest(user, {
                                                requestSent = true
                                            }, {})
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        val user = currentUser
        if (showEditProfileDialog && user != null) {
            EditProfileDialog(
                viewModel = viewModel,
                currentUser = user,
                onDismiss = { showEditProfileDialog = false }
            )
        }
    }
}

// --- Empty State ---
@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = "Empty", tint = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(72.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = desc, fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center)
    }
}

// --- List Items ---
@Composable
fun FriendItem(friend: User, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = SurfaceSlate,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                AsyncImage(
                    model = friend.avatarUrl.ifEmpty { "https://i.ibb.co.com/gJZVWhG/avatar1.png" },
                    contentDescription = friend.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(50.dp).clip(CircleShape).background(BorderSlate)
                )
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (friend.status == "online") AccentGreen else TextSecondary)
                        .border(2.dp, SurfaceSlate, CircleShape)
                        .align(Alignment.BottomEnd)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = friend.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(text = if (friend.status == "online") "Active Now" else "Offline", fontSize = 13.sp, color = TextSecondary)
            }

            Icon(Icons.Default.ChevronRight, contentDescription = "GoToChat", tint = TextSecondary)
        }
    }
}

@Composable
fun FriendRequestItem(request: FriendRequest, onAccept: () -> Unit, onReject: () -> Unit) {
    Surface(
        color = SurfaceSlate,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = request.senderAvatar.ifEmpty { "https://i.ibb.co.com/gJZVWhG/avatar1.png" },
                    contentDescription = request.senderName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(45.dp).clip(CircleShape).background(BorderSlate)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = request.senderName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(text = "Sent you a friend request", fontSize = 12.sp, color = TextSecondary)
                }
            }

            Row {
                IconButton(onClick = onAccept, modifier = Modifier.background(PrimaryNeon, CircleShape).size(36.dp)) {
                    Icon(Icons.Default.Check, contentDescription = "Accept", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onReject, modifier = Modifier.background(BrightRose, CircleShape).size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Reject", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun SearchUserItem(user: User, requestSent: Boolean, onSendRequest: () -> Unit) {
    Surface(
        color = SurfaceSlate,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = user.avatarUrl.ifEmpty { "https://i.ibb.co.com/gJZVWhG/avatar1.png" },
                    contentDescription = user.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(45.dp).clip(CircleShape).background(BorderSlate)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = user.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(text = user.email, fontSize = 12.sp, color = TextSecondary)
                }
            }

            if (requestSent) {
                Button(onClick = {}, enabled = false, colors = ButtonDefaults.buttonColors(disabledContainerColor = BorderSlate)) {
                    Text("Request Sent", fontSize = 12.sp, color = TextSecondary)
                }
            } else {
                Button(
                    onClick = onSendRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Add Friend", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

// --- Chat Room Screen ---
@Composable
fun ChatRoomScreen(
    viewModel: ChatViewModel,
    friend: User,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
    hasPermissions: Boolean
) {
    val messages by viewModel.activeMessages.collectAsState()
    val isUploading by viewModel.isUploadingImage.collectAsState()

    var textMsg by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var previewImageUrl by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.uploadImageToImgbb(it, "be818775a9cf13f8fd5b46cc805b2179") { url ->
                viewModel.sendMessage(friend.uid, "", url)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceSlate)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                        AsyncImage(
                            model = friend.avatarUrl.ifEmpty { "https://i.ibb.co.com/gJZVWhG/avatar1.png" },
                            contentDescription = friend.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(BorderSlate)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = friend.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                             Text(
                                text = if (friend.status == "online") "Active Now" else "Offline",
                                fontSize = 11.sp,
                                color = if (friend.status == "online") AccentGreen else TextSecondary
                            )
                        }
                    }

                    // Audio & Video Call Actions
                    Row {
                        IconButton(onClick = {
                            if (hasPermissions) {
                                viewModel.startCall(friend, "audio")
                            } else {
                                onRequestPermissions()
                            }
                        }) {
                            Icon(Icons.Default.Phone, contentDescription = "Voice Call", tint = PrimaryNeon)
                        }
                        IconButton(onClick = {
                            if (hasPermissions) {
                                viewModel.startCall(friend, "video")
                            } else {
                                onRequestPermissions()
                            }
                        }) {
                            Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = SecondaryIndigo)
                        }
                    }
                }
            }
        },
        containerColor = DeepSlate
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Messages List
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.ChatBubbleOutline,
                        title = "Start Chatting",
                        desc = "Send a message or a photo to start a conversation."
                    )
                } else {
                    val lastReadMessageId = remember(messages) {
                        messages.lastOrNull { it.senderId == viewModel.currentUser.value?.uid && it.isRead }?.messageId
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages) { msg ->
                            val isMe = msg.senderId == "me" || msg.senderId == viewModel.currentUser.value?.uid
                            MessageBubble(
                                message = msg,
                                isMe = isMe,
                                showReadReceipt = msg.messageId == lastReadMessageId,
                                friendAvatarUrl = friend.avatarUrl,
                                onImageClick = { url -> previewImageUrl = url }
                            )
                        }
                    }
                }

                if (isUploading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = SurfaceSlate,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = PrimaryNeon, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Uploading photo...", color = TextPrimary, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // Input Bar
            Surface(
                color = SurfaceSlate,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach File", tint = PrimaryNeon)
                    }

                    OutlinedTextField(
                        value = textMsg,
                        onValueChange = { textMsg = it },
                        placeholder = { Text("Type a message...") },
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryNeon,
                            unfocusedBorderColor = BorderSlate,
                            focusedContainerColor = DeepSlate,
                            unfocusedContainerColor = DeepSlate
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (textMsg.trim().isNotEmpty()) {
                                viewModel.sendMessage(friend.uid, textMsg.trim())
                                textMsg = ""
                            }
                        },
                        modifier = Modifier.background(PrimaryNeon, CircleShape)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }
        }
    }

    previewImageUrl?.let { url ->
        ImagePreviewDialog(imageUrl = url, onDismiss = { previewImageUrl = null })
    }
}

// --- Immersive Fullscreen Image Viewer with Zoom, Pan, Double-tap Reset, and Download ---
@Composable
fun ImagePreviewDialog(
    imageUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset += offsetChange * scale
    }

    androidx.compose.ui.window.Dialog(
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        ),
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state = state)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = androidx.compose.ui.geometry.Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "Preview Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }

            // High-fidelity Immersive Top Bar Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                Text(
                    text = "Photo Preview",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = {
                        downloadImage(context, imageUrl)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download Image",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

// Background downloader using standard Android DownloadManager API (saves to public Downloads folder)
fun downloadImage(context: android.content.Context, url: String) {
    try {
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
            setTitle("Downloading Photo")
            setDescription("Saving shared photo to your Downloads folder")
            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "ConnectChat_${System.currentTimeMillis()}.png")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        downloadManager.enqueue(request)
        android.widget.Toast.makeText(context, "Download started...", android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Download failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
fun MessageBubble(
    message: Message,
    isMe: Boolean,
    showReadReceipt: Boolean = false,
    friendAvatarUrl: String = "",
    onImageClick: (String) -> Unit = {}
) {
    if (message.isCallLog) {
        // Premium Centered Call Log Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = SurfaceSlate,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderSlate),
                tonalElevation = 2.dp,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isMissed = message.callDuration.isEmpty()
                    val icon = if (message.callType == "video") {
                        if (isMissed) Icons.Default.VideoCall else Icons.Default.VideoCall
                    } else {
                        if (isMissed) Icons.Default.Phone else Icons.Default.Phone
                    }
                    val iconColor = if (isMissed) BrightRose else AccentGreen

                    Icon(
                        imageVector = icon,
                        contentDescription = "Call Log",
                        tint = iconColor,
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = message.text,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formatTimestamp(message.timestamp),
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    } else {
        val bubbleColor = if (isMe) PrimaryNeon else SurfaceSlate
        val alignment = if (isMe) Alignment.End else Alignment.Start
        val shape = if (isMe) {
            RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
        } else {
            RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = alignment
        ) {
            Surface(
                color = bubbleColor,
                shape = shape,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (message.imageUrl.isNotEmpty()) {
                        AsyncImage(
                            model = message.imageUrl,
                            contentDescription = "Shared photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .padding(bottom = 4.dp)
                                .clickable { onImageClick(message.imageUrl) }
                        )
                    }

                    if (message.text.isNotEmpty()) {
                        Text(
                            text = message.text,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            if (isMe && showReadReceipt && friendAvatarUrl.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                AsyncImage(
                    model = friendAvatarUrl,
                    contentDescription = "Seen",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(1.dp, PrimaryNeon, CircleShape)
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return try {
        val sdf = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US)
        sdf.format(java.util.Date(timestamp))
    } catch (e: Exception) {
        ""
    }
}

// --- Firebase Custom Configuration Settings Screen ---
@Composable
fun FirebaseConfigScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    var apiKey by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var projectId by remember { mutableStateOf("") }
    var databaseUrl by remember { mutableStateOf("") }

    val currentConfig = viewModel.firebaseHelper.getSavedConfig()

    LaunchedEffect(currentConfig) {
        apiKey = currentConfig.apiKey
        appId = currentConfig.appId
        projectId = currentConfig.projectId
        databaseUrl = currentConfig.databaseUrl
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceSlate)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Text("Firebase Configuration", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            }
        },
        containerColor = DeepSlate
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Text(
                text = "Configure your own Firebase Realtime Database parameters below to unlock secure, 100% real-time messaging and high-fidelity video/audio call capabilities.",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Firebase API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNeon, unfocusedBorderColor = BorderSlate)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it },
                label = { Text("Firebase Application ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNeon, unfocusedBorderColor = BorderSlate)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = projectId,
                onValueChange = { projectId = it },
                label = { Text("Firebase Project ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNeon, unfocusedBorderColor = BorderSlate)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = databaseUrl,
                onValueChange = { databaseUrl = it },
                label = { Text("Firebase Realtime Database URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNeon, unfocusedBorderColor = BorderSlate)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.updateFirebaseConfig(
                        FirebaseConfig(apiKey, appId, projectId, databaseUrl)
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save & Connect", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (currentConfig.apiKey.isNotEmpty()) {
                Button(
                    onClick = {
                        viewModel.clearFirebaseConfig()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrightRose),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Reset to Demo Mode", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

// --- Global Agora Video/Audio Call Overlay Screen ---
@Composable
fun CallOverlay(
    call: CallSession,
    currentUser: User?,
    viewModel: ChatViewModel,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit
) {
    val agoraManager = viewModel.agoraManager
    val isIncoming = currentUser?.uid == call.receiverId && call.status == "ringing"
    val remoteUserUid by viewModel.remoteUserUid.collectAsState()

    var secondsActive by remember { mutableStateOf(0) }
    LaunchedEffect(call.status) {
        if (call.status == "active") {
            secondsActive = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                secondsActive += 1
            }
        }
    }

    val durationText = remember(secondsActive) {
        val mins = secondsActive / 60
        val secs = secondsActive % 60
        String.format("%02d:%02d", mins, secs)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .clickable(enabled = false) {}, // Intercept touch
        contentAlignment = Alignment.Center
    ) {
        if (call.status == "active" && call.type == "video") {
            // Real Video Streaming Canvas
            Box(modifier = Modifier.fillMaxSize()) {
                // Remote streaming view (Large background canvas)
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).apply {
                            val targetUid = remoteUserUid ?: 0
                            agoraManager.setupRemoteVideo(this, targetUid) // Render remote partner
                        }
                    },
                    update = { view ->
                        remoteUserUid?.let { uid ->
                            agoraManager.setupRemoteVideo(view, uid)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Local streaming preview (Small floating tile)
                Box(
                    modifier = Modifier
                        .size(120.dp, 160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White, RoundedCornerShape(12.dp))
                        .background(Color.DarkGray)
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                agoraManager.setupLocalVideo(this) // Render my camera
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Foreground call metadata and overlays
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize().padding(36.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 48.dp)
            ) {
                AsyncImage(
                    model = if (isIncoming) call.callerAvatar.ifEmpty { "https://i.ibb.co.com/gJZVWhG/avatar1.png" } else "https://i.ibb.co.com/gJZVWhG/avatar1.png",
                    contentDescription = "Partner Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .border(3.dp, if (call.type == "video") SecondaryIndigo else PrimaryNeon, CircleShape)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (isIncoming) call.callerName else "Calling...",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (call.status == "active") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AccentGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Connected ($durationText)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = AccentGreen
                        )
                    }
                } else {
                    Text(
                        text = "Ringing...",
                        fontSize = 15.sp,
                        color = TextSecondary
                    )
                }
            }

            // Controls Row
            Row(
                modifier = Modifier.padding(bottom = 36.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isIncoming) {
                    // Incoming Ringing Controls
                    IconButton(
                        onClick = onReject,
                        modifier = Modifier
                            .size(64.dp)
                            .background(BrightRose, CircleShape)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Decline Call", tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    Spacer(modifier = Modifier.width(48.dp))

                    IconButton(
                        onClick = onAccept,
                        modifier = Modifier
                            .size(64.dp)
                            .background(AccentGreen, CircleShape)
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = "Accept Call", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                } else {
                    // Ongoing Call Controls
                    var micMuted by remember { mutableStateOf(false) }
                    var videoMuted by remember { mutableStateOf(false) }

                    IconButton(
                        onClick = {
                            micMuted = !micMuted
                            agoraManager.muteLocalAudio(micMuted)
                        },
                        modifier = Modifier
                            .size(54.dp)
                            .background(if (micMuted) BorderSlate else SurfaceSlate, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute Mic",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    IconButton(
                        onClick = onEnd,
                        modifier = Modifier
                            .size(64.dp)
                            .background(BrightRose, CircleShape)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Hang Up", tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    if (call.type == "video") {
                        Spacer(modifier = Modifier.width(24.dp))

                        IconButton(
                            onClick = {
                                videoMuted = !videoMuted
                                agoraManager.muteLocalVideo(videoMuted)
                            },
                            modifier = Modifier
                                .size(54.dp)
                                .background(if (videoMuted) BorderSlate else SurfaceSlate, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (videoMuted) Icons.Default.VideocamOff else Icons.Default.Videocam,
                                contentDescription = "Mute Cam",
                                tint = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        IconButton(
                            onClick = { agoraManager.switchCamera() },
                            modifier = Modifier
                                .size(54.dp)
                                .background(SurfaceSlate, CircleShape)
                        ) {
                            Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Switch Camera", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// --- Floating System-Like Call Notification Banner ---
@Composable
fun FloatingCallBanner(
    call: CallSession,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .windowInsetsPadding(WindowInsets.statusBars)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = SurfaceSlate),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(1.dp, BorderSlate),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = call.callerAvatar.ifEmpty { "https://i.ibb.co.com/gJZVWhG/avatar1.png" },
                contentDescription = "Caller Avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, PrimaryNeon, CircleShape)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.callerName,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (call.type == "video") "Incoming Video Call" else "Incoming Audio Call",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onDecline,
                modifier = Modifier
                    .size(36.dp)
                    .background(BrightRose, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "Decline",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            IconButton(
                onClick = onAccept,
                modifier = Modifier
                    .size(36.dp)
                    .background(AccentGreen, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = "Accept",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// --- Edit Profile Dialog ---
@Composable
fun EditProfileDialog(
    viewModel: ChatViewModel,
    currentUser: User,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentUser.name) }
    var avatarUrl by remember { mutableStateOf(currentUser.avatarUrl) }
    var isUploading by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isUploading = true
            viewModel.uploadImageToImgbb(it, "be818775a9cf13f8fd5b46cc805b2179") { url ->
                if (url.isNotEmpty()) {
                    avatarUrl = url
                } else {
                    android.widget.Toast.makeText(context, "Avatar upload failed.", android.widget.Toast.LENGTH_SHORT).show()
                }
                isUploading = false
            }
        }
    }

    val avatars = listOf(
        "https://i.ibb.co.com/gJZVWhG/avatar1.png",
        "https://i.ibb.co.com/pZ6mBfT/avatar2.png",
        "https://i.ibb.co.com/LNDgZ1q/avatar3.png",
        "https://i.ibb.co.com/VMR2L5H/avatar4.png",
        "https://i.ibb.co.com/PZcZ80S/avatar5.png",
        "https://i.ibb.co.com/zXG640G/avatar6.png"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Edit Profile Settings", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    AsyncImage(
                        model = avatarUrl.ifEmpty { "https://i.ibb.co.com/gJZVWhG/avatar1.png" },
                        contentDescription = "Selected Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .border(2.dp, PrimaryNeon, CircleShape)
                            .background(BorderSlate)
                    )
                    if (isUploading) {
                        CircularProgressIndicator(
                            color = PrimaryNeon,
                            modifier = Modifier.size(24.dp).align(Alignment.Center)
                        )
                    } else {
                        IconButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier
                                .size(32.dp)
                                .background(PrimaryNeon, CircleShape)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Upload Avatar", tint = DeepSlate, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Select a preset avatar:", color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    avatars.take(3).forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Avatar Option",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (avatarUrl == url) 3.dp else 1.dp,
                                    color = if (avatarUrl == url) PrimaryNeon else BorderSlate,
                                    shape = CircleShape
                                )
                                .clickable { avatarUrl = url }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    avatars.drop(3).forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Avatar Option",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (avatarUrl == url) 3.dp else 1.dp,
                                    color = if (avatarUrl == url) PrimaryNeon else BorderSlate,
                                    shape = CircleShape
                                )
                                .clickable { avatarUrl = url }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryNeon,
                        unfocusedBorderColor = BorderSlate,
                        focusedLabelColor = PrimaryNeon
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.trim().isNotEmpty()) {
                        viewModel.updateProfile(name, avatarUrl, {
                            android.widget.Toast.makeText(context, "Profile updated successfully!", android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }, {
                            android.widget.Toast.makeText(context, "Update failed: $it", android.widget.Toast.LENGTH_SHORT).show()
                        })
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon, contentColor = DeepSlate)
            ) {
                Text("Save Changes", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = SurfaceSlate
    )
}
