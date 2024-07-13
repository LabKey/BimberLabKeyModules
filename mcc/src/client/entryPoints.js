module.exports = {
  apps: [{
    name: 'dashboard',
    title: 'MCC Data',
    permissionClasses: ['org.labkey.api.security.permissions.ReadPermission'],
    path: './src/client/Dashboard'
  },{
    name: 'dashboardWebpart',
    title: 'MCC Data',
    path: './src/client/Dashboard/webpart',
    generateLib: true
  },{
    name: 'animalRequest',
    title: 'Animal Requests',
    permissionClasses: ['org.labkey.api.security.permissions.ReadPermission'],
    path: './src/client/AnimalRequest'
  }, {
    name: 'requestReview',
    title: 'Animal Request Review',
    permissionClasses: ['org.labkey.api.security.permissions.ReadPermission'],
    template: 'home',
    path: './src/client/RequestReview'
  },{
    name: 'geneticsPlot',
    title: 'Marmoset Genetics',
    permissionClasses: ['org.labkey.api.security.permissions.ReadPermission'],
    path: './src/client/GeneticsPlot'
  },{
    name: 'geneticsPlotWebpart',
    title: 'Marmoset Genetics',
    permissionClasses: ['org.labkey.api.security.permissions.ReadPermission'],
    path: './src/client/GeneticsPlot/webpart'
  }]
};
