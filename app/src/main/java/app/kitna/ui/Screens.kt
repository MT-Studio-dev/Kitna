package app.kitna.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.text.font.FontWeight
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
    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text(if(owed==0L&&owe==0L)"Your overall balance is ${money(0)}" else if(owed==owe)"Your net balance is ${money(0)}" else if(owed>owe)"Overall, you are owed ${money(owed-owe)}" else "Overall, you owe ${money(owe-owed)}",fontSize=19.sp,color=if(owed==owe)MaterialTheme.colorScheme.onSurface else if(owed>owe)Owed else Owe,fontWeight=FontWeight.Medium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)){
            Column(Modifier.weight(1f)){Text("You owe",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(money(owe),fontSize=19.sp,color=Owe)}
            Column(Modifier.weight(1f)){Text("You are owed",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(money(owed),fontSize=19.sp,color=Owed)}
        }
    }
    HorizontalDivider(thickness=0.5.dp)
}
@Composable internal fun DetailSummary(description:String,net:Long,onSettle:()->Unit,onBalances:()->Unit,archived:Boolean,hasDebts:Boolean=false,appearance:Group?=null){
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){if(appearance!=null)GroupAvatar(appearance,52);Text(description,Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)}
        Text(if(net==0L&&hasDebts)"Net ${money(0)} · repayments remain" else if(net==0L)"You're all settled up" else if(net>0)"You are owed ${money(net)}" else "You owe ${money(-net)}",color=if(net==0L)MaterialTheme.colorScheme.onSurface else if(net>0)Owed else Owe,fontSize=23.sp,fontWeight=FontWeight.Medium)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Button(onSettle,enabled=!archived,colors=ButtonDefaults.buttonColors(containerColor=Owe)){Text("Settle up")}
            OutlinedButton(onBalances){Text("Balances")}
        }
        if(archived)Text("Archived group · restore it in group settings to make changes.",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable internal fun FilterBar(count:Int,active:Boolean,onFilter:()->Unit){Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text("$count expenses",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton(onFilter){Glyph("filter",color=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(6.dp));Text(if(active)"Filters applied" else "Filters")}}}
@Composable internal fun SectionLabel(title:String){Text(title,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)}
internal fun LazyListScope.expenseRows(book:Book,expenses:List<Expense>,onClick:(Expense)->Unit,showGroup:Boolean=false){
    if(expenses.isEmpty())item{EmptyState("No expenses yet","Record a shared expense, or adjust your search and filters.")}
    expenses.groupBy{it.date.take(7)}.forEach{(month,rows)->
        item(key="month:$month"){SectionLabel(runCatching{LocalDate.parse("$month-01").format(DateTimeFormatter.ofPattern("MMMM yyyy"))}.getOrDefault(month))}
        items(rows,key={"expense:${it.id}"}){e->
            val paid=e.payers["self"]?:0;val share=e.shares["self"]?:0;val net=paid-share
            Row(Modifier.fillMaxWidth().clickable{onClick(e)}.padding(horizontal=16.dp,vertical=15.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Column(Modifier.width(28.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(e.date.takeLast(2),fontSize=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                CategoryIcon(e.category)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(e.description,fontWeight=FontWeight.Medium);Text(if(e.payers.size==1)"${book.personName(e.payers.keys.first())} paid ${money(e.amount)}" else "${e.payers.size} people paid ${money(e.amount)}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(if(showGroup)"${e.category} · ${book.groupName(e.groupId)}" else e.category,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Column(horizontalAlignment=Alignment.End){Text(if(net>0)"you lent" else if(net<0)"you borrowed" else "your share",fontSize=11.sp,color=if(net>0)Owed else if(net<0)Owe else MaterialTheme.colorScheme.onSurfaceVariant);Text(money(if(net==0L)share else kotlin.math.abs(net)),fontWeight=FontWeight.Medium,color=if(net>0)Owed else if(net<0)Owe else MaterialTheme.colorScheme.onSurface)}
            }
            HorizontalDivider(Modifier.padding(start=68.dp),thickness=0.5.dp)
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
internal fun LazyListScope.accountContent(book:Book,theme:String,onTheme:(String)->Unit,onProfile:()->Unit,onReports:()->Unit,onCsv:()->Unit,onBackup:()->Unit,onRestore:()->Unit,onTrash:()->Unit,onErase:()->Unit){
    item{Row(Modifier.fillMaxWidth().clickable(onClick=onProfile).padding(22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){Avatar(book.personName("self"),size=64);Column(Modifier.weight(1f)){Text(book.personName("self"),fontSize=24.sp,fontWeight=FontWeight.SemiBold);Text("Personal expense book",color=MaterialTheme.colorScheme.onSurfaceVariant)};Glyph("edit")}}
    item{SectionLabel("Your book")}
    item{AccountRow("Spending insights","Categories, months and people","report",onReports)}
    item{AccountRow("Recently deleted","Restore expenses and repayments","receipt",onTrash)}
    item{SectionLabel("Data & backups")}
    item{AccountRow("Export CSV","Open your expenses in a spreadsheet","download",onCsv)}
    item{AccountRow("Save a backup","Complete JSON copy, including history","download",onBackup)}
    item{AccountRow("Restore a backup","Replace this book with a saved copy","receipt",onRestore)}
    item{SectionLabel("Appearance")}
    item{Column(Modifier.padding(20.dp)){Choice("Theme",listOf("System","Light","Dark").map{it to it},theme,onTheme)}}
    item{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Stored only on your device",fontWeight=FontWeight.Medium);Text("App updates keep this book. Install updates over Kitna using the same signing key. Uninstalling or clearing app data removes local records, so save a backup before changing phones.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){KitnaMark(size=28);Text("Kitna 0.3 · INR",style=MaterialTheme.typography.labelSmall)}}}
    item{TextButton(onErase,Modifier.padding(horizontal=10.dp)){Text("Erase all local data",color=MaterialTheme.colorScheme.error)}}
}
@Composable private fun AccountRow(title:String,subtitle:String,icon:String,onClick:()->Unit){ListItem(modifier=Modifier.clickable(onClick=onClick),headlineContent={Text(title)},supportingContent={Text(subtitle,fontSize=12.sp)},leadingContent={Glyph(icon,color=MaterialTheme.colorScheme.primary)},trailingContent={Glyph("chevron")})}
@Composable private fun Choice(label:String,options:List<Pair<String,String>>,value:String,onChange:(String)->Unit){var expanded by remember{mutableStateOf(false)};Box{OutlinedButton({expanded=true},Modifier.fillMaxWidth()){Text("$label: ${options.find{it.first==value}?.second?:"Choose"}")};DropdownMenu(expanded,{expanded=false}){options.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={onChange(id);expanded=false})}}}
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
