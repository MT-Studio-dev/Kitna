@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kitna.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kitna.core.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal data class ExpenseFilter(val category:String="All categories",val person:String="all",val from:String="",val to:String="",val min:String="",val max:String="") {
    val active get()=category!="All categories"||person!="all"||from.isNotBlank()||to.isNotBlank()||min.isNotBlank()||max.isNotBlank()
    fun validate(){if(from.isNotBlank())Accounting.validDate(from);if(to.isNotBlank())Accounting.validDate(to);require(from.isBlank()||to.isBlank()||from<=to){"From date must be before the to date."};val a=min.takeIf{it.isNotBlank()}?.let{Accounting.money(it)};val b=max.takeIf{it.isNotBlank()}?.let{Accounting.money(it)};require(a==null||b==null||a<=b){"Minimum must be less than maximum."}}
    fun matches(book:Book,e:Expense,query:String):Boolean = (category=="All categories"||category==e.category)&&(person=="all"||person in (e.payers.keys+e.shares.keys))&&(from.isBlank()||e.date>=from)&&(to.isBlank()||e.date<=to)&&(min.isBlank()||e.amount>=Accounting.money(min))&&(max.isBlank()||e.amount<=Accounting.money(max))&&BookSearch.expense(book,e,query)
    companion object{val Saver=listSaver<ExpenseFilter,String>(save={listOf(it.category,it.person,it.from,it.to,it.min,it.max)},restore={ExpenseFilter(it[0],it[1],it[2],it[3],it[4],it[5])})}
}
@Composable internal fun OverallSummary(balances:Map<String,Long>) {
    val owed=balances.values.filter{it>0}.sum();val owe=-balances.values.filter{it<0}.sum()
    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(buildAnnotatedString {
            append(if(owed==0L&&owe==0L) "You're all settled up" else if(owed>=owe) "Overall, you are owed " else "Overall, you owe ")
            if(owed!=0L||owe!=0L) withStyle(SpanStyle(color=if(owed>=owe)Owed else Owe,fontWeight=FontWeight.SemiBold)){append(money(kotlin.math.abs(owed-owe)))}
        },style=MaterialTheme.typography.titleMedium)
        if(owe>0&&owed>0)Text("You owe ${money(owe)} \u00b7 You're owed ${money(owed)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun DetailSummary(description:String,net:Long,onSettle:()->Unit,onBalances:()->Unit,archived:Boolean,hasDebts:Boolean=false,appearance:Group?=null,onSummary:(()->Unit)?=null,onBudget:(()->Unit)?=null,onMembers:(()->Unit)?=null){
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(appearance!=null)GroupHeader(appearance,description)
        else Text(description,Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(net==0L&&hasDebts) "Net ${money(0)} \u00b7 repayments remain" else if(net==0L) "You're all settled up" else if(net>0) "You are owed ${money(net)}" else "You owe ${money(-net)}",modifier=Modifier.padding(horizontal=20.dp),color=if(net==0L)MaterialTheme.colorScheme.onSurface else if(net>0)Owed else Owe,style=MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onSettle,enabled=!archived,modifier=Modifier.heightIn(min=48.dp)){Text("Settle up")}
            OutlinedButton(onBalances,Modifier.heightIn(min=48.dp)){Text("Balances")}
            if(onSummary!=null)OutlinedButton(onSummary,Modifier.heightIn(min=48.dp)){Text("Trip summary")}
            if(onBudget!=null)OutlinedButton(onBudget,Modifier.heightIn(min=48.dp)){Text("Budget")}
            if(onMembers!=null)OutlinedButton(onMembers,Modifier.heightIn(min=48.dp)){Text("Invite friends")}
        }
        if(archived)Text("Archived group \u00b7 restore it in group settings to make changes.",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable internal fun FilterBar(count:Int,active:Boolean,onFilter:()->Unit){Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text("$count expenses",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton(onFilter){Glyph("filter",color=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(6.dp));Text(if(active)"Filters applied" else "Filters")}}}
@Composable internal fun SectionLabel(title:String){Text(title,Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=16.dp).semantics{heading()},style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurface)}
internal fun LazyListScope.expenseRows(book:Book,expenses:List<Expense>,onClick:(Expense)->Unit,showGroup:Boolean=false,keyPrefix:String=""){
    val settings=book.groups.associateBy{it.id}
    val largest=book.expenses.filterNot{it.deleted}.groupBy{it.groupId}.mapValues{(_,rows)->rows.maxOfOrNull{it.amount}?:0L}
    if(expenses.isEmpty())item{EmptyState("No expenses yet","Record a shared expense, or adjust your search and filters.")}
    expenses.groupBy{it.date.take(7)}.forEach{(month,rows)->
        item(key="${keyPrefix}month:$month"){SectionLabel(runCatching{LocalDate.parse("$month-01").format(DateTimeFormatter.ofPattern("MMMM yyyy"))}.getOrDefault(month))}
        items(rows,key={"${keyPrefix}expense:${it.id}"}){e->
            val net=(e.payers["self"]?:0)-(e.shares["self"]?:0)
            val emphasis=expenseEmphasis(e.amount,settings[e.groupId]?.budget,largest[e.groupId]?:0L)
            if(LocalDensity.current.fontScale>1.25f||LocalConfiguration.current.screenWidthDp<340){
                Column(Modifier.fillMaxWidth().background(if(emphasis==ExpenseEmphasis.Large)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.32f) else MaterialTheme.colorScheme.background).clickable{onClick(e)}.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        CategoryIcon(e.category,size=36)
                        Text(e.description,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,fontWeight=if(emphasis==ExpenseEmphasis.Large)FontWeight.SemiBold else FontWeight.Normal)
                    }
                    Text(runCatching{LocalDate.parse(e.date).format(DateTimeFormatter.ofPattern("d MMM"))}.getOrDefault(e.date)+" · "+if(e.payers.size==1)"${book.personName(e.payers.keys.first())} paid ${money(e.amount)}" else "${e.payers.size} people paid ${money(e.amount)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text(if(net>0)"you lent" else if(net<0)"you borrowed" else "your share",style=MaterialTheme.typography.labelSmall,color=if(net>0)Owed else if(net<0)Owe else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(money(if(net==0L)e.shares["self"]?:0 else kotlin.math.abs(net)),style=if(emphasis==ExpenseEmphasis.Large)MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,fontWeight=if(emphasis==ExpenseEmphasis.Large)FontWeight.Bold else FontWeight.Normal,color=if(net>0)Owed else if(net<0)Owe else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(showGroup)Text(book.groupName(e.groupId),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }else{
            Row(Modifier.fillMaxWidth().background(if(emphasis==ExpenseEmphasis.Large)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.32f) else MaterialTheme.colorScheme.background).clickable{onClick(e)}.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Column(Modifier.width(30.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(runCatching{LocalDate.parse(e.date).format(DateTimeFormatter.ofPattern("MMM"))}.getOrDefault(""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(e.date.takeLast(2),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                CategoryIcon(e.category,size=36)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text(e.description,style=MaterialTheme.typography.bodyLarge,fontWeight=if(emphasis==ExpenseEmphasis.Large)FontWeight.SemiBold else FontWeight.Normal)
                    Text(if(e.payers.size==1)"${book.personName(e.payers.keys.first())} paid ${money(e.amount)}" else "${e.payers.size} people paid ${money(e.amount)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(showGroup)Text(book.groupName(e.groupId),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment=Alignment.End,modifier=Modifier.widthIn(max=112.dp)){
                    Text(if(net>0)"you lent" else if(net<0)"you borrowed" else "your share",style=MaterialTheme.typography.labelSmall,color=if(net>0)Owed else if(net<0)Owe else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(money(if(net==0L)e.shares["self"]?:0 else kotlin.math.abs(net)),style=if(emphasis==ExpenseEmphasis.Large)MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,fontWeight=if(emphasis==ExpenseEmphasis.Large)FontWeight.Bold else FontWeight.Normal,color=if(net>0)Owed else if(net<0)Owe else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
        }
    }
}
internal fun LazyListScope.reportsContent(book:Book,expenses:List<Expense>,filter:ExpenseFilter,groupId:String?,onGroup:(String?)->Unit,onFilters:()->Unit){
    val selected=expenses.filter{groupId==null||it.groupId==groupId};val total=selected.sumOf{it.shares["self"]?:0}
    item{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Choice("Group",listOf("all" to "All groups")+book.groups.map{it.id to it.name},groupId?:"all"){onGroup(it.takeUnless{it=="all"})}
        Text("Your share of spending",color=MaterialTheme.colorScheme.onSurfaceVariant);Text(money(total),fontSize=36.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
        Text("${selected.size} expenses · repayments excluded",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Total paid by you");Text(money(selected.sumOf{it.payers["self"]?:0}),fontWeight=FontWeight.Medium)}
    };FilterBar(selected.size,filter.active,onFilters)}
    item{SectionLabel("Spending by category")}
    if(selected.isEmpty())item{EmptyState("Your spending, at a glance","Add an expense to see category and monthly breakdowns.")}
    val categories=selected.groupBy{it.category}.mapValues{(_,rows)->rows.sumOf{it.shares["self"]?:0}}.toList().sortedByDescending{it.second}
    items(categories){(name,amount)->Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){CategoryIcon(name,size=32);Text(name,Modifier.weight(1f));Text(money(amount))};LinearProgressIndicator(progress={if(total==0L)0f else amount.toFloat()/total},modifier=Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.primary)}}
    item{SectionLabel("Monthly spending")}
    items(selected.groupBy{it.date.take(7)}.toSortedMap(reverseOrder()).toList()){(month,rows)->ListItem(headlineContent={Text(LocalDate.parse("$month-01").format(DateTimeFormatter.ofPattern("MMMM yyyy")))},supportingContent={Text("${rows.size} expenses")},trailingContent={Text(money(rows.sumOf{it.shares["self"]?:0}),fontWeight=FontWeight.Medium)})}
    item{SectionLabel("Spending by person")}
    items(book.people){person->val amount=selected.sumOf{it.shares[person.id]?:0};ListItem(leadingContent={Avatar(person.name,size=36)},headlineContent={Text(person.name)},trailingContent={Text(money(amount))})}
}
internal fun LazyListScope.accountContent(book:Book,theme:String,onTheme:(String)->Unit,onProfile:()->Unit,onReports:()->Unit,onCsv:()->Unit,onBackup:()->Unit,onRestore:()->Unit,onTrash:()->Unit,onErase:()->Unit,onHelp:(()->Unit)?=null,onAbout:(()->Unit)?=null,sessionEmail:String?=null,onSettings:(()->Unit)?=null,onLogout:(()->Unit)?=null,onShared:(()->Unit)?=null){
    item{Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
        Avatar(book.personName("self"),size=64)
        Column(Modifier.weight(1f)){Text(book.personName("self"),style=MaterialTheme.typography.titleLarge);Text(sessionEmail?:"Local expense book",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        TextButton(onProfile){Text("Edit")}
    }}
    if(onShared!=null)item{Column(Modifier.padding(horizontal=16.dp,vertical=16.dp)){
        MangaPanel(color=MaterialTheme.colorScheme.primaryContainer){
            Text("Everyone's on the same page",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Shared groups, receipts and reports. Every feature is free.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onPrimaryContainer)
            Button(onShared,Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(sessionEmail==null)"Shared groups & Google sign-in" else "Manage shared groups")}
        }
    }}
    item{SectionLabel("Preferences")}
    item{AccountRow("Spending insights","Categories, months and people","report",onReports)}
    if(onSettings!=null)item{AccountRow("Settings","Appearance, notifications and your data","filter",onSettings)}
    else{
        item{SectionLabel("Data & backups")}
        item{AccountRow("Recently deleted","Restore expenses and repayments","receipt",onTrash)}
        item{AccountRow("Export CSV","Open your expenses in a spreadsheet","download",onCsv)}
        item{AccountRow("Save a backup","Complete ZIP with records, history and receipt photos","download",onBackup)}
        item{AccountRow("Restore a backup","Restore a ZIP backup or an older JSON book","receipt",onRestore)}
        item{Column(Modifier.padding(20.dp)){Choice("Theme",listOf("System","Light","Dark").map{it to it},theme,onTheme)}}
        item{TextButton(onErase,Modifier.padding(horizontal=16.dp).heightIn(min=48.dp)){Text("Erase all local data",color=MaterialTheme.colorScheme.error)}}
    }
    if(onHelp!=null)item{AccountRow("Help & support","Get help, understand splits and contact us","help",onHelp)}
    if(onAbout!=null)item{AccountRow("About Kitna","Created by MT Studio","help",onAbout)}
    if(onLogout!=null)item{OutlinedButton(onLogout,Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp).heightIn(min=48.dp)){Text("Log out")}}
    item{Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){KitnaMark(size=32);Column{Text("Kitna \u00b7 INR",style=MaterialTheme.typography.bodySmall);Text("Created by MT Studio",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
}
@Composable private fun AccountRow(title:String,subtitle:String,icon:String,onClick:()->Unit){
    Surface(modifier=Modifier.fillMaxWidth().clickable(onClick=onClick)){
        Row(Modifier.heightIn(min=72.dp).padding(horizontal=24.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
            Glyph(icon,color=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,style=MaterialTheme.typography.bodyLarge);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Glyph("chevron",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun Choice(label:String,options:List<Pair<String,String>>,value:String,onChange:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded=true },Modifier.fillMaxWidth()) { Text("$label: ${options.find { it.first==value }?.second ?: "Choose"}") }
        DropdownMenu(expanded,{ expanded=false }) { options.forEach { (id,name) -> DropdownMenuItem(text={ Text(name) },onClick={ onChange(id);expanded=false }) } }
    }
}
@Composable internal fun FiltersDialog(book:Book,current:ExpenseFilter,onApply:(ExpenseFilter)->Unit,onClose:()->Unit){
    var draft by rememberSaveable(stateSaver=ExpenseFilter.Saver){mutableStateOf(current)};var error by rememberSaveable{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onClose,title={Text("Filter expenses")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Choice("Category",(listOf("All categories")+Categories+book.expenses.map{it.category}).distinct().map{it to it},draft.category){draft=draft.copy(category=it)}
        Choice("Person",listOf("all" to "Everyone")+book.people.map{it.id to it.name},draft.person){draft=draft.copy(person=it)}
        OutlinedTextField(draft.from,{draft=draft.copy(from=it)},label={Text("From · YYYY-MM-DD")},singleLine=true)
        OutlinedTextField(draft.to,{draft=draft.copy(to=it)},label={Text("To · YYYY-MM-DD")},singleLine=true)
        OutlinedTextField(draft.min,{draft=draft.copy(min=it)},label={Text("Minimum total · INR")},singleLine=true)
        OutlinedTextField(draft.max,{draft=draft.copy(max=it)},label={Text("Maximum total · INR")},singleLine=true)
        TextButton({draft=ExpenseFilter();error=null}){Text("Clear all filters")};error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton({runCatching{draft.validate()}.onSuccess{onApply(draft);onClose()}.onFailure{error=it.message?:"Use dates in YYYY-MM-DD format."}}){Text("Apply")}},dismissButton={TextButton(onClose){Text("Cancel")}})
}
