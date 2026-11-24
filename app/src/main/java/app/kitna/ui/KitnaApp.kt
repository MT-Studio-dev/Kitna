package app.kitna.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import app.kitna.KitnaViewModel
import app.kitna.core.*
import app.kitna.data.BookBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun KitnaApp(theme:String,onTheme:(String)->Unit,vm:KitnaViewModel=viewModel()) {
    val book by vm.book.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val fileTransferBusy by vm.fileTransferBusy.collectAsStateWithLifecycle()
    val cloud by vm.cloud.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable{mutableStateOf("Groups")}
    var route by rememberSaveable{mutableStateOf("main")}
    var returnRoute by rememberSaveable{mutableStateOf("main")}
    var detailReturn by rememberSaveable{mutableStateOf("main")}
    var groupId by rememberSaveable{mutableStateOf<String?>(null)}
    var personId by rememberSaveable{mutableStateOf<String?>(null)}
    var expenseId by rememberSaveable{mutableStateOf<String?>(null)}
    var settlementId by rememberSaveable{mutableStateOf<String?>(null)}
    var query by rememberSaveable{mutableStateOf("")}
    var search by rememberSaveable{mutableStateOf(false)}
    var archived by rememberSaveable{mutableStateOf(false)}
    var filters by rememberSaveable{mutableStateOf(false)}
    var filter by rememberSaveable(stateSaver=ExpenseFilter.Saver){mutableStateOf(ExpenseFilter())}
    var balanceSheet by rememberSaveable{mutableStateOf(false)}
    var restoreUri by rememberSaveable{mutableStateOf<String?>(null)}
    var erase by remember{mutableStateOf(false)}
    var logout by remember{mutableStateOf(false)}
    var duplicateExpense by rememberSaveable{mutableStateOf(false)}
    val scope=rememberCoroutineScope();val context=LocalContext.current;val snackbar=remember{SnackbarHostState()}
    val onboardingPreferences=remember(context){context.getSharedPreferences("onboarding",android.content.Context.MODE_PRIVATE)}
    var onboarding by rememberSaveable{mutableStateOf(!onboardingPreferences.getBoolean("complete",false))}
    var onboardingError by rememberSaveable{mutableStateOf<String?>(null)}
    fun finishOnboarding(){onboardingPreferences.edit().putBoolean("complete",true).apply();onboarding=false}
    LaunchedEffect(book){val loaded=book;if(loaded!=null&&(loaded.people.size>1||loaded.groups.isNotEmpty()||loaded.people.firstOrNull{it.id=="self"}?.name!="You"))finishOnboarding()}
    LaunchedEffect(cloud.pendingInvite){if(cloud.pendingInvite!=null){finishOnboarding();returnRoute="main";groupId=null;route="shared"}}
    LaunchedEffect(cloud.openGroupId, book != null) { cloud.openGroupId?.let { id -> if (book != null) { if (book?.groups?.any { it.id == id } == true) { finishOnboarding();tab="Groups";groupId=id;route="group";query="";search=false };vm.cloud.clearGroupNavigation() } } }
    val backups=remember(vm.store){BookBackup(context.applicationContext,vm.store)}
    val searchFocus=remember{FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(search,route,book!=null){if(book!=null&&search&&route in listOf("main","group","friend"))searchFocus.requestFocus()}
    fun edit(target:String,back:String=route){duplicateExpense=false;returnRoute=back;route=target}
    fun clearNavigation(){tab="Groups";route="main";returnRoute="main";detailReturn="main";groupId=null;personId=null;expenseId=null;settlementId=null;query="";search=false;filter=ExpenseFilter();archived=false}
    fun detail(e:Expense){expenseId=e.id;detailReturn=route;route="expenseDetail"}
    fun notify(result:Result<Unit>,message:String){scope.launch{snackbar.showSnackbar(result.fold({message},{it.message?:"Could not complete that"}))}}
    fun reportFailure(failure:Throwable,fallback:String) {
        val message=failure.message ?: fallback
        if(vm.book.value==null) vm.error.value=message
        else scope.launch { snackbar.showSnackbar(message) }
    }
    val exportBackup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        val snapshot=book
        if(uri!=null&&snapshot!=null)scope.launch{
            notify(vm.fileTransfer{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri)).use{backups.exportZip(snapshot,it)}}},"Complete backup saved")
        }
    }
    val exportCsv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->val snapshot=book;if(uri!=null&&snapshot!=null)scope.launch{notify(vm.fileTransfer{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use{it.write(BookExport.csv(snapshot))}}},"CSV exported")}}
    val importBackup=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            restoreUri=uri.toString()
        }
    }
    fun restore(){importBackup.launch(arrayOf("application/zip","application/x-zip-compressed","application/json","text/plain","application/octet-stream"))}
    val b=book
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
        if(b==null)Column(Modifier.fillMaxSize().safeDrawingPadding(),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            if(error==null){CircularProgressIndicator();Text("Opening your records…",Modifier.padding(20.dp))}
            else{Text("Your records could not be opened",style=MaterialTheme.typography.titleLarge);Text(error!!,Modifier.padding(24.dp));OutlinedButton({vm.retryLoad()}){Text("Retry opening records")};OutlinedButton({restore()}){Text("Restore a backup")}}
        }else if(onboarding)OnboardingScreen(onComplete={name->scope.launch{vm.renameSelf(name).onSuccess{finishOnboarding()}.onFailure{onboardingError=it.message}}},onSignIn={finishOnboarding();groupId=null;edit("shared","main")},error=onboardingError)
        else when(route){
            "shared"->SharedAccountScreen(b,vm,groupId){route=if(returnRoute=="group"&&vm.book.value?.groups?.none{it.id==groupId}==true)"main" else returnRoute}
            "groupSettings"->{ val selected=b.groups.find{it.id==groupId}; if(selected!=null)GroupSettingsScreen(b,selected,vm,onEdit={edit("groupEdit","groupSettings")},onMembers={edit("shared","groupSettings")},onBack={route=if(vm.book.value?.groups?.any{it.id==groupId}==true)"group" else "main"}) else LaunchedEffect(Unit){route="main"} }
            "settings"->SettingsScreen(theme,onTheme,onCsv={exportCsv.launch("kitna-expenses-${LocalDate.now()}.csv")},onBackup={exportBackup.launch("kitna-backup-${LocalDate.now()}.zip")},onRestore={restore()},onTrash={route="trash"},onClear={erase=true},onBack={route="main"},onNotifications={route="notifications"})
            "notifications"->NotificationSettingsScreen { route="settings" }
            "about"->AboutScreen(onBack={route=returnRoute})
            "help"->HelpScreen(onBack={route=returnRoute},syncStatus=if(cloud.session==null)"Local records" else "Google account connected · ${b.pendingChanges.size} pending changes",onShowOnboarding={onboarding=true})
            "expenseEdit"->ExpenseEditor(b,b.expenses.find{it.id==expenseId},groupId,vm,duplicate=duplicateExpense){route=returnRoute}
            "groupEdit"->GroupEditor(b,b.groups.find{it.id==groupId},vm,onCreated={id,share->groupId=id;tab="Groups";route="group";query="";filter=ExpenseFilter();if(share)scope.launch{vm.cloud.shareGroup(id).onFailure{snackbar.showSnackbar(it.message?:"Group saved. Open Members to retry sharing.")}}}){route=if(returnRoute=="group"&&vm.book.value?.groups?.none{it.id==groupId}!=false)"main" else returnRoute}
            "friendEdit"->PersonEditor(b,b.people.find{it.id==personId},vm){route=if(returnRoute=="friend"&&vm.book.value?.people?.none{it.id==personId}!=false)"main" else returnRoute}
            "settlementEdit"->{
                val pair=if(returnRoute=="friend")b.groups.find{it.id==groupId}?.let{Accounting.transfers(b,it.id)}?.firstOrNull{(it.from=="self"&&it.to==personId)||(it.to=="self"&&it.from==personId)}else null
                SettlementEditor(b,b.settlements.find{it.id==settlementId},groupId,pair?.from?:if(returnRoute=="friend")"self" else null,pair?.to?:if(returnRoute=="friend")personId else null,vm){route=returnRoute}
            }
            "expenseDetail"->ExpenseDetails(b,expenseId.orEmpty(),vm,onEdit={expenseId=it.id;groupId=it.groupId;edit("expenseEdit","expenseDetail")},onDuplicate={expenseId=it.id;groupId=it.groupId;edit("expenseEdit","expenseDetail");duplicateExpense=true}){route=detailReturn}
            "tripSummary"->{
                val trip=b.groups.find{it.id==groupId}
                if(trip!=null)TripSummaryScreen(b,trip){route="group"}
                else EmptyState("Group unavailable","Return to your groups to choose a trip.","Back"){route="main"}
            }
            else->{
                val group=b.groups.find{it.id==groupId};val friend=b.people.find{it.id==personId};val main=route=="main"
                BackHandler(!main){route="main";query="";filter=ExpenseFilter()}
                BackHandler(main&&search){search=false;query="";keyboard?.hide()}
                val title=when(route){"group"->"";"friend"->friend?.name?:"Friend";"reports"->"Spending insights";"trash"->"Recently deleted";else->if(search)"Search" else tab}
                val balances=Accounting.friendBalances(b)
                Scaffold(containerColor=MaterialTheme.colorScheme.background,snackbarHost={SnackbarHost(snackbar)},topBar={Column{
                    TopAppBar(colors=TopAppBarDefaults.topAppBarColors(containerColor=if(route=="group"&&group!=null)groupColor(group.color) else MaterialTheme.colorScheme.background,navigationIconContentColor=if(route=="group"&&group!=null&&groupColor(group.color).luminance()<=.42f)androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface,actionIconContentColor=if(route=="group"&&group!=null&&groupColor(group.color).luminance()<=.42f)androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface),title={Text(title,fontWeight=FontWeight.Medium,fontSize=23.sp)},navigationIcon={if(!main)ActionIcon("back","Back"){route="main";query="";filter=ExpenseFilter()}else if(search)ActionIcon("back","Close search"){search=false;query="";keyboard?.hide()}},actions={
                        if(route in listOf("group","friend")||(main&&tab!="Account"))ActionIcon("search","Search"){search=!search;if(!search)query=""}
                        if(route=="group")ActionIcon("settings","Group settings"){edit("groupSettings")}
                        else if(route=="friend")ActionIcon("edit","Edit friend"){edit("friendEdit")}
                        else if(main&&tab in listOf("Friends","Groups"))ActionIcon("add",if(tab=="Friends")"Add friend" else "Create group"){if(tab=="Friends"){personId=null;edit("friendEdit")}else{groupId=null;edit("groupEdit")}}
                    })
                    if(search&&route!="reports")OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp).focusRequester(searchFocus),label={Text(if(main)"Search all records" else "Search expenses")},placeholder={Text("Expense, category, person or amount")},singleLine=true,leadingIcon={Glyph("search")},trailingIcon={if(query.isNotEmpty())ActionIcon("close","Clear search"){query=""}},keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide()}))
                }},bottomBar={if(main||route in listOf("group","friend"))NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp){listOf("Groups","Friends","Activity","Account").forEach{name->NavigationBarItem(selected=tab==name,onClick={tab=name;route="main";query="";search=false;filter=ExpenseFilter()},icon={Glyph(name.lowercase(),color=if(tab==name)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)},label={Text(name)},colors=NavigationBarItemDefaults.colors(indicatorColor=androidx.compose.ui.graphics.Color.Transparent,selectedIconColor=MaterialTheme.colorScheme.primary,selectedTextColor=MaterialTheme.colorScheme.primary))}}},floatingActionButton={
                    if((main&&tab in listOf("Friends","Groups"))||route=="friend"||(route=="group"&&group?.archived==false))ExtendedFloatingActionButton(onClick={scope.launch{
                        if(b.people.size<2){personId=null;edit("friendEdit")}
                        else if(route=="friend"&&personId!=null){groupId=b.groups.firstOrNull{it.kind=="Direct"&&personId in it.members}?.id?:"friend:$personId";expenseId=null;edit("expenseEdit")}
                        else{if(main)groupId=null;expenseId=null;edit("expenseEdit")}
                    }},containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,icon={Glyph("add",color=MaterialTheme.colorScheme.onPrimary)},text={Text(if(b.people.size<2)"Add friend" else "Add expense")})
                }){padding->
                    val filtered=b.expenses.filter{e->!e.deleted&&(route!="group"||e.groupId==groupId)&&(route!="friend"||personId in (e.payers.keys+e.shares.keys))&&filter.matches(b,e,query)}.sortedByDescending{it.date}
                    LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(bottom=100.dp)){
                        when{
                            main&&search&&query.isNotBlank()->searchContent(b,query,onExpense=::detail,onGroup={groupId=it;route="group";query="";filter=ExpenseFilter()},onPerson={personId=it;groupId=null;route="friend";query="";filter=ExpenseFilter()},onPayment={settlementId=it.id;groupId=it.groupId;edit("settlementEdit")})
                            route=="reports"->reportsContent(b,filtered,filter,groupId,onGroup={groupId=it},onFilters={filters=true})
                            route=="trash"->{
                                item{Text("Restore deleted expenses and repayments here.",Modifier.padding(20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                if(b.expenses.none{it.deleted}&&b.settlements.none{it.deleted})item{EmptyState("Nothing in the bin","Your deleted records will appear here.")}
                                items(b.expenses.filter{it.deleted},key={"expense:${it.id}"}){e->ListItem(headlineContent={Text(e.description)},supportingContent={Text("${b.groupName(e.groupId)} · ${money(e.amount)}")},trailingContent={TextButton({scope.launch{notify(vm.restoreExpense(e.id),"Expense restored")}}){Text("Restore")}})}
                                items(b.settlements.filter{it.deleted},key={"settlement:${it.id}"}){s->ListItem(headlineContent={Text("${b.personName(s.from)} paid ${b.personName(s.to)}")},supportingContent={Text(money(s.amount))},trailingContent={TextButton({scope.launch{notify(vm.saveSettlement(s.copy(deleted=false)),"Repayment restored")}}){Text("Restore")}})}
                            }
                            route=="group"||route=="friend"->{
                                item{
                                    val relevant=if(route=="group")listOfNotNull(group)else b.groups.filter{personId in it.members}
                                    val hasDebts=relevant.any{g->Accounting.transfers(b,g.id).any{t->(t.from=="self"||t.to=="self")&&(route=="group"||t.from==personId||t.to==personId)}}
                                    DetailSummary(
                                        if(route=="group")group?.members?.joinToString{b.personName(it)}.orEmpty()else "Across groups and direct expenses",
                                        if(route=="group"&&group!=null)Accounting.balances(b,group.id)["self"]?:0 else balances[personId]?:0,
                                        onSettle={
                                            settlementId=null
                                            if(route=="friend")groupId=relevant.firstOrNull{g->!g.archived&&Accounting.transfers(b,g.id).any{t->(t.from=="self"&&t.to==personId)||(t.to=="self"&&t.from==personId)}}?.id
                                                ?:relevant.firstOrNull{!it.archived&&it.kind=="Direct"}?.id?:"friend:$personId"
                                            edit("settlementEdit")
                                        },onBalances={balanceSheet=true},archived=route=="group"&&group?.archived==true,hasDebts=hasDebts,appearance=if(route=="group")group else null,onSummary=if(route=="group")({route="tripSummary"})else null,onBudget=if(route=="group")({edit("groupEdit")})else null,onMembers=if(route=="group")({edit("groupSettings")})else null
                                    )
                                    if(route=="group"&&group!=null){
                                        if(group.budget!=null)TripBudgetCard(b,group){edit("groupEdit")}
                                        if(b.expenses.none{it.groupId==group.id&&!it.deleted})GroupInviteCard(b,group,vm){edit("shared")}
                                    }
                                    FilterBar(filtered.size,filter.active){filters=true}
                                }
                                expenseRows(b,filtered,::detail)
                                val payments=b.settlements.filter{!it.deleted&&(route!="group"||it.groupId==groupId)&&(route!="friend"||it.from==personId||it.to==personId)}.sortedByDescending{it.date}
                                if(payments.isNotEmpty())item{SectionLabel("Repayments")}
                                items(payments,key={"payment:${it.id}"}){s->ListItem(modifier=Modifier.clickable{settlementId=s.id;edit("settlementEdit")},headlineContent={Text("${b.personName(s.from)} paid ${b.personName(s.to)}")},supportingContent={Text("${s.date} · ${s.method}")},trailingContent={Text(money(s.amount),color=Owed)},leadingContent={Glyph("check",color=Owed)})}
                            }
                            tab=="Friends"->{
                                item{OverallSummary(balances)}
                                val people=b.people.filter{it.id!="self"&&it.name.contains(query,true)}.sortedBy{it.name.lowercase()}
                                if(people.isEmpty())item{EmptyState("Keep money simple","Add a friend to track what you owe each other.","Add a friend"){personId=null;edit("friendEdit")}}
                                items(people,key={it.id}){p->val count=b.groups.count{it.kind!="Direct"&&p.id in it.members};ListItem(modifier=Modifier.clickable{personId=p.id;groupId=null;route="friend";query="";filter=ExpenseFilter()},leadingContent={Avatar(p.name)},headlineContent={Text(p.name,fontWeight=FontWeight.Medium)},supportingContent={Text(if(count==0)"Direct expenses" else if(count==1)"1 shared group" else "$count shared groups",fontSize=12.sp)},trailingContent={BalanceText(balances[p.id]?:0)});HorizontalDivider(Modifier.padding(start=80.dp),thickness=0.5.dp)}
                            }
                            tab=="Groups"->{
                                item{OverallSummary(balances);Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("Your groups",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton({groupId=null;edit("shared")}){Text("Join group")};FilterChip(archived,{archived=!archived},label={Text("Show archived")})}}
                                val groups=b.groups.filter{it.kind!="Direct"&&(archived||!it.archived)&&it.name.contains(query,true)}.sortedBy{it.name.lowercase()}
                                if(groups.isEmpty())item{EmptyState("Life's better together","Trips, home, dinner plans — keep every shared expense in a group.","Start a group"){groupId=null;edit("groupEdit")}}
                                items(groups,key={it.id}){g->GroupListRow(b,g){groupId=g.id;route="group";query="";filter=ExpenseFilter()}}
                                item{TextButton({groupId=null;query="";filter=ExpenseFilter();route="reports"},Modifier.padding(12.dp)){Glyph("report",color=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(8.dp));Text("View spending insights")}}
                            }
                            tab=="Activity"->{
                                val activity=b.activity.filter{it.message.contains(query,true)||b.groupName(it.groupId).contains(query,true)}.asReversed()
                                if(activity.isEmpty())item{EmptyState("All caught up","Expenses, payments and changes will appear here.")}
                                items(activity,key={it.id}){a->ListItem(leadingContent={val g=b.groups.find{it.id==a.groupId};if(g!=null)GroupAvatar(g,40)else Avatar(b.personName("self"),size=40)},headlineContent={Text(a.message)},supportingContent={Text("${b.groupName(a.groupId)} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(a.time))}",fontSize=12.sp)})}
                            }
                            else->{
                                accountContent(b,theme,onTheme,onProfile={personId="self";edit("friendEdit")},onReports={groupId=null;query="";filter=ExpenseFilter();route="reports"},onCsv={exportCsv.launch("kitna-expenses-${LocalDate.now()}.csv")},onBackup={exportBackup.launch("kitna-backup-${LocalDate.now()}.zip")},onRestore={restore()},onTrash={route="trash"},onErase={erase=true},onHelp={edit("help")},onAbout={edit("about")},sessionEmail=cloud.session?.email,onSettings={edit("settings")},onLogout=if(cloud.session==null)null else ({logout=true}),onShared={groupId=null;edit("shared")})
                            }
                        }
                    }
                }
            }
        }
    }
    if(filters&&b!=null)FiltersDialog(b,filter,{filter=it}){filters=false}
    if(balanceSheet&&b!=null)BalancesSheet(b,if(route=="group")b.groups.filter{it.id==groupId}else b.groups.filter{personId in it.members},onDismiss={balanceSheet=false},onSettle={group->balanceSheet=false;groupId=group.id;settlementId=null;edit("settlementEdit")})
    if(logout)AlertDialog(onDismissRequest={logout=false},title={Text("Log out of Kitna?")},text={Text("Sync or discard pending changes first. Shared groups will be available when you sign in again; your local groups remain on this phone.")},confirmButton={TextButton({logout=false;scope.launch{vm.cloud.signOut().onSuccess{clearNavigation();snackbar.showSnackbar("Logged out")}.onFailure{snackbar.showSnackbar(it.message?:"Could not log out")}}}){Text("Log out")}},dismissButton={TextButton({logout=false}){Text("Cancel")}})
    if(restoreUri!=null)AlertDialog(onDismissRequest={restoreUri=null},title={Text("Replace your local records?")},text={Text("This replaces your local book with the selected ZIP or JSON backup. Shared groups on the server stay unchanged. Sign out before restoring, and save a complete backup first if you need to keep current records and photos.")},confirmButton={TextButton({val selected=Uri.parse(restoreUri!!);restoreUri=null;scope.launch{vm.fileTransfer{require(cloud.session==null){"Sign out in Shared groups before restoring. Backups restore local records; they do not replace live server groups."};restoreSelectedBackup(context,vm.store,selected)}.onSuccess{clearNavigation();snackbar.showSnackbar("Backup restored")}.onFailure{reportFailure(it,"Restore failed")}}}){Text("Restore backup")}},dismissButton={TextButton({restoreUri=null}){Text("Cancel")}})
    if(erase)AlertDialog(onDismissRequest={erase=false},title={Text("Erase all local data?")},text={Text("All people, groups, expenses, repayments, comments, history and receipt photos will be removed. This cannot be undone without a backup.")},confirmButton={TextButton({erase=false;scope.launch{vm.resetBook().onSuccess{clearNavigation();snackbar.showSnackbar("Local records erased")}.onFailure{snackbar.showSnackbar(it.message?:"Could not erase records")}}}){Text("Erase everything",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton({erase=false}){Text("Cancel")}})
    if(fileTransferBusy)AlertDialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false),title={Text("Working on your backup")},text={Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){CircularProgressIndicator(Modifier.size(28.dp));Text("Please wait while your records and files are copied.")}},confirmButton={})
}
