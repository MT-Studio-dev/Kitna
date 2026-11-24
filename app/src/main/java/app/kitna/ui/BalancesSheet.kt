@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.kitna.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kitna.core.*

@Composable internal fun BalancesSheet(book:Book,groups:List<Group>,onDismiss:()->Unit,onSettle:((Group)->Unit)?=null){
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){
            Scaffold(topBar={TopAppBar(title={Text("Balances")},navigationIcon={ActionIcon("back","Back",onDismiss)})}){padding->
                LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(bottom=32.dp)){
                    groups.forEach{group->
                        val balances=Accounting.balances(book,group.id)
                        val transfers=Accounting.transfers(book,group.id)
                        if(groups.size>1)item{SectionLabel(group.name)}
                        items(group.members,key={"${group.id}:$it"}){person->
                            val net=balances[person]?:0L
                            val relevant=transfers.filter{it.from==person||it.to==person}
                            var expanded by rememberSaveable(group.id,person){mutableStateOf(person=="self")}
                            Column(Modifier.fillMaxWidth().padding(bottom=10.dp)){
                                Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.padding(horizontal=16.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                                    Avatar(book.personName(person),size=46)
                                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                        Text(book.personName(person),style=MaterialTheme.typography.bodyLarge)
                                        Text(if(net>0)"gets back ${money(net)} in total" else if(net<0)"owes ${money(-net)} in total" else if(relevant.isNotEmpty())"Net settled; repayments remain" else "is settled up",color=if(net>0)Owed else if(net<0)Owe else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
                                    }
                                    ActionIcon(if(expanded)"collapse" else "expand",if(expanded)"Collapse ${book.personName(person)}" else "Expand ${book.personName(person)}"){expanded=!expanded}
                                }
                                if(expanded){
                                    if(relevant.isEmpty())Text("No repayments needed",Modifier.padding(start=76.dp,end=20.dp,bottom=16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    relevant.forEach{transfer->
                                        Row(Modifier.fillMaxWidth().padding(start=46.dp,end=20.dp,bottom=18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                            Avatar(book.personName(if(transfer.from==person)transfer.to else transfer.from),size=28)
                                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                                Text("${book.personName(transfer.from)} owes ${money(transfer.amount)} to ${book.personName(transfer.to)}",style=MaterialTheme.typography.bodyMedium)
                                                if(onSettle!=null&&!group.archived)OutlinedButton({onSettle(group)},Modifier.heightIn(min=48.dp)){Text("Settle up")}
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item{TextButton(onDismiss,Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Done")}}
                }
            }
        }
    }
}
