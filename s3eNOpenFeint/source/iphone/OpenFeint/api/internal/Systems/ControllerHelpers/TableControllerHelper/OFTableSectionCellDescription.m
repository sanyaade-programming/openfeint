//  Copyright 2009-2010 Aurora Feint, Inc.
// 
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//  
//  	http://www.apache.org/licenses/LICENSE-2.0
//  	
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

#import "OFTableSectionCellDescription.h"
#import "OFResource.h"
#import "OFResource+ObjC.h"
#import "OFDependencies.h"

@implementation OFTableSectionCellDescription

@synthesize resource;
@synthesize controllerName;

+ (id)cellController:(NSString*)controllerName andResource:(OFResource*)resource
{
	OFTableSectionCellDescription* cellDescription = [OFTableSectionCellDescription new];
	cellDescription.resource = resource;
	cellDescription.controllerName = controllerName;
	return cellDescription;
}

- (void)dealloc
{
	self.resource = nil;
	self.controllerName = nil;
	
	[super dealloc];
}

@end
